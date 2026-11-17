/* =====================================================================
   SP cho API chart Cost Monitoring (9 chart: PR/MO/Guide x OT/T&SL/RM)
   Cần chạy trước: 01_cost_master.sql, 07_cost_ot_daily.sql, 02_fn_cost_actual_daily.sql
   Actual OT: dbo.CostOtDaily (nạp qua POST /api/cost-monitoring/ot/import), cutoff = ngày cuối có dòng OT
   Index đề xuất: 05_cost_monitoring_indexes.sql
   Fluctuating TGT + ngày làm việc lấy từ [F2Database].[dbo].[F2_Working_Date]
   EXEC [KVH].dbo.sp_CostMonitoringChart @MonthStart = '2026-10-01', @SimuWeek = NULL;

   Hiệu năng: các bước trung gian ghi ra bảng tạm (#WDay, #Tgt, #Act, #Base) để
   mỗi bảng nguồn chỉ đọc 1 lần. Bản cũ dùng CTE Base 2 lần (Base + LastAct) nên
   SQL Server tính lại cả fn_CostActualDaily (quét CostTool / CostRm) 2 lần.
   ===================================================================== */
USE [KVH];
GO
CREATE OR ALTER PROCEDURE dbo.sp_CostMonitoringChart
    @MonthStart DATE,                       -- ngày bất kỳ trong tháng, SP tự lấy ngày 1
    @SimuWeek   NVARCHAR(10) = NULL         -- NULL = tuần Simu mới nhất có số; hoặc 'Simu_W3'
AS
BEGIN
    SET NOCOUNT ON;

    SET @MonthStart = DATEFROMPARTS(YEAR(@MonthStart), MONTH(@MonthStart), 1);
    DECLARE @Month    NVARCHAR(10) = CONVERT(NVARCHAR(7), @MonthStart, 102);   -- '2026.10'
    DECLARE @MonthEnd DATE = EOMONTH(@MonthStart);
    DECLARE @Today    DATE = CAST(GETDATE() AS DATE);
    DECLARE @AsOf     DATE = CASE WHEN DATEADD(DAY, -1, @Today) < @MonthEnd THEN DATEADD(DAY, -1, @Today) ELSE @MonthEnd END; -- T&SL
    DECLARE @AsOfRM   DATE = CASE WHEN DATEADD(DAY, -2, @Today) < @MonthEnd THEN DATEADD(DAY, -2, @Today) ELSE @MonthEnd END; -- RM

    /* ---------- 1. Ngày làm việc + tỷ lệ Sales+Inven theo ngày (F2_Working_Date) ----------
       GROUP BY ngày: bảng có >1 dòng / ngày thì Base bị nhân dòng, Act_MTD (cộng dồn) cộng 1 ngày nhiều lần
       -> lệch với tổng chi theo ngày. Mỗi ngày chỉ giữ 1 dòng. */
    CREATE TABLE #WDay (
        D            DATE NOT NULL PRIMARY KEY,
        WD_All       FLOAT NULL,
        Adj_Ratio_PR FLOAT NULL,
        Adj_Ratio_MO FLOAT NULL,
        Adj_Ratio_GU FLOAT NULL
    );

    INSERT INTO #WDay (D, WD_All, Adj_Ratio_PR, Adj_Ratio_MO, Adj_Ratio_GU)
    SELECT CAST(w.[Date] AS DATE),
           MAX(TRY_CONVERT(FLOAT, w.WD_All)),
           MAX(TRY_CONVERT(FLOAT, w.Adj_Ratio_PR)),
           MAX(TRY_CONVERT(FLOAT, w.Adj_Ratio_MO)),
           MAX(TRY_CONVERT(FLOAT, w.Adj_Ratio_GU))
    FROM [F2Database].[dbo].[F2_Working_Date] w
    WHERE w.[Date] >= @MonthStart AND w.[Date] < DATEADD(DAY, 1, @MonthEnd)
    GROUP BY CAST(w.[Date] AS DATE);

    /* ---------- 2. Target theo khung (F2_PL_TGT, đọc 1 lần) ---------- */
    CREATE TABLE #Tgt (
        Div    NVARCHAR(10) NOT NULL,
        Chart  NVARCHAR(10) NOT NULL,
        FC_TGT FLOAT NULL,
        PL_TGT FLOAT NULL,
        BP     FLOAT NULL,
        PRIMARY KEY (Div, Chart)
    );

    ;WITH Charts AS (
        SELECT * FROM (VALUES
            (N'PR',    N'PRESS', N'OT',   N'OverTime'), (N'PR',    N'PRESS', N'T&SL', N'Tools'), (N'PR',    N'PRESS', N'RM', N'RM'),
            (N'MO',    N'MOLD',  N'OT',   N'OverTime'), (N'MO',    N'MOLD',  N'T&SL', N'Tools'), (N'MO',    N'MOLD',  N'RM', N'RM'),
            (N'Guide', N'GUIDE', N'OT',   N'OverTime'), (N'Guide', N'GUIDE', N'T&SL', N'Tools'), (N'Guide', N'GUIDE', N'RM', N'RM')
        ) v(Div, TgtDiv, Chart, Cate)
    ),
    Src AS (
        SELECT RTRIM([Div]) AS Div, RTRIM([Cate]) AS Cate, RTRIM([DataType]) AS DataType, SUM([KUsd]) AS KUsd
        FROM [F2Database].[dbo].[F2_PL_TGT]
        WHERE [Month] = @Month
        GROUP BY RTRIM([Div]), RTRIM([Cate]), RTRIM([DataType])
    ),
    SrcAgg AS (                                  -- 1 lần đọc Src: FC, BP và tuần Simu mới nhất
        SELECT Div, Cate, DataType, KUsd,
               CASE WHEN DataType LIKE N'Simu[_]W%' AND ISNULL(KUsd, 0) <> 0
                         AND (@SimuWeek IS NULL OR DataType = @SimuWeek)
                    THEN ROW_NUMBER() OVER (
                             PARTITION BY Div, Cate,
                                          CASE WHEN DataType LIKE N'Simu[_]W%' AND ISNULL(KUsd, 0) <> 0
                                                    AND (@SimuWeek IS NULL OR DataType = @SimuWeek)
                                               THEN 1 ELSE 0 END
                             ORDER BY DataType DESC)
               END AS SimuRn
        FROM Src
    )
    INSERT INTO #Tgt (Div, Chart, FC_TGT, PL_TGT, BP)
    SELECT c.Div, c.Chart,
           MAX(CASE WHEN s.DataType = N'FC' THEN s.KUsd END),
           MAX(CASE WHEN s.SimuRn = 1 THEN s.KUsd END),
           MAX(CASE WHEN s.DataType = N'BP' THEN s.KUsd END)
    FROM Charts c
    LEFT JOIN SrcAgg s ON s.Div = c.TgtDiv AND s.Cate = c.Cate
    GROUP BY c.Div, c.Chart;

    /* ---------- 3. Actual theo ngày (fn_CostActualDaily, quét CostTool / CostRm 1 lần) ---------- */
    CREATE TABLE #Act (
        Div   NVARCHAR(10) NOT NULL,
        Chart NVARCHAR(10) NOT NULL,
        D     DATE NOT NULL,
        Amt   FLOAT NULL,
        PRIMARY KEY (Div, Chart, D)
    );

    INSERT INTO #Act (Div, Chart, D, Amt)
    SELECT Div, Chart, D, SUM(Amt)
    FROM dbo.fn_CostActualDaily(@MonthStart, @MonthEnd)
    WHERE D IS NOT NULL
    GROUP BY Div, Chart, D;

    /* OT: số nạp thủ công từ file FY26 (dbo.CostOtDaily) -> Actual chỉ tới ngày cuối CÓ DÒNG OT (≤ hôm qua),
       không coi ngày chưa nạp là 0 (Estimate nối từ ngày có số thật). Chưa nạp gì trong tháng -> Act NULL. */
    CREATE TABLE #OtCut (
        Div    NVARCHAR(10) NOT NULL PRIMARY KEY,
        Cutoff DATE NULL
    );

    INSERT INTO #OtCut (Div, Cutoff)
    SELECT Div, MAX(D)
    FROM #Act
    WHERE Chart = N'OT' AND D <= @AsOf
    GROUP BY Div;

    /* ---------- 4. Base: 9 khung x số ngày trong tháng ---------- */
    CREATE TABLE #Base (
        Div       NVARCHAR(10) NOT NULL,
        Chart     NVARCHAR(10) NOT NULL,
        D         DATE NOT NULL,
        FC_TGT    FLOAT NULL,
        PL_TGT    FLOAT NULL,
        BP        FLOAT NULL,
        Fluct_TGT FLOAT NULL,
        PL_MTD    FLOAT NULL,
        Pace_MTD  FLOAT NULL,
        Act_MTD   FLOAT NULL,
        PRIMARY KEY (Div, Chart, D)
    );

    ;WITH Cal AS (
        SELECT @MonthStart AS D
        UNION ALL SELECT DATEADD(DAY, 1, D) FROM Cal WHERE D < @MonthEnd
    ),
    CalWD AS (                                  -- không có dòng trong F2_Working_Date thì coi là ngày làm việc
        SELECT c.D, ISNULL(w.WD_All, 1) AS WD
        FROM Cal c LEFT JOIN #WDay w ON w.D = c.D
    )
    INSERT INTO #Base (Div, Chart, D, FC_TGT, PL_TGT, BP, Fluct_TGT, PL_MTD, Pace_MTD, Act_MTD)
    SELECT t.Div, t.Chart, k.D, t.FC_TGT, t.PL_TGT, t.BP,
           -- Fluctuating TGT = FC x (1 + Ratio); từ hôm nay trở đi giữ tỷ lệ của hôm qua (giống Excel cột S)
           t.FC_TGT * (1 + CASE t.Div WHEN N'PR' THEN wr.Adj_Ratio_PR
                                      WHEN N'MO' THEN wr.Adj_Ratio_MO
                                      ELSE wr.Adj_Ratio_GU END),
           t.PL_TGT * SUM(k.WD) OVER (PARTITION BY t.Div, t.Chart ORDER BY k.D ROWS UNBOUNDED PRECEDING)
                    / NULLIF(SUM(k.WD) OVER (PARTITION BY t.Div, t.Chart), 0),
           -- Pace cho Estimate: ưu tiên PL TGT, không có thì dùng FC TGT
           COALESCE(t.PL_TGT, t.FC_TGT) * SUM(k.WD) OVER (PARTITION BY t.Div, t.Chart ORDER BY k.D ROWS UNBOUNDED PRECEDING)
                    / NULLIF(SUM(k.WD) OVER (PARTITION BY t.Div, t.Chart), 0),
           CASE WHEN k.D <= x.Cutoff THEN
                SUM(CASE WHEN k.D <= x.Cutoff THEN ISNULL(a.Amt, 0) END)
                    OVER (PARTITION BY t.Div, t.Chart ORDER BY k.D ROWS UNBOUNDED PRECEDING)
           END
    FROM #Tgt t
    CROSS JOIN CalWD k
    CROSS APPLY (SELECT CASE WHEN t.Chart = N'RM' THEN @AsOfRM
                             WHEN t.Chart = N'OT' THEN (SELECT oc.Cutoff FROM #OtCut oc WHERE oc.Div = t.Div)
                             ELSE @AsOf END AS Cutoff) x
    LEFT JOIN #WDay wr ON wr.D = CASE WHEN k.D >= @Today THEN DATEADD(DAY, -1, @Today) ELSE k.D END
    LEFT JOIN #Act a ON a.Div = t.Div AND a.Chart = t.Chart AND a.D = k.D
    OPTION (MAXRECURSION 40);

    /* ---------- 5. Kết quả: Estimate nối từ điểm Actual cuối ---------- */
    ;WITH LastAct AS (
        SELECT Div, Chart, D AS LastD, Act_MTD AS LastAct, Pace_MTD AS LastPace
        FROM (SELECT Div, Chart, D, Act_MTD, Pace_MTD,
                     ROW_NUMBER() OVER (PARTITION BY Div, Chart ORDER BY D DESC) rn
              FROM #Base WHERE Act_MTD IS NOT NULL) z
        WHERE rn = 1
    )
    SELECT b.Div, b.Chart, b.D,
           ROUND(b.FC_TGT, 3)  AS FC_TGT,
           ROUND(b.PL_TGT, 3)  AS PL_TGT,
           ROUND(b.BP, 3)      AS BP,
           ROUND(b.PL_MTD, 3)  AS PL_MTD,
           ROUND(b.Act_MTD, 3) AS Act_MTD,
           -- Estimate: nối từ điểm Actual cuối (D = LastD -> = LastAct), sau đó cộng thêm theo pace
           ROUND(CASE WHEN l.LastD IS NOT NULL AND b.D >= l.LastD
                      THEN l.LastAct + b.Pace_MTD - l.LastPace END, 3) AS Est_MTD,
           ROUND(b.Fluct_TGT, 3) AS Fluct_TGT
    FROM #Base b
    LEFT JOIN LastAct l ON l.Div = b.Div AND l.Chart = b.Chart
    ORDER BY b.Div, b.Chart, b.D;
END
GO
