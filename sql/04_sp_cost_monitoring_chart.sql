/* =====================================================================
   SP cho API chart Cost Monitoring (9 chart: PR/MO/Guide x OT/T&SL/RM)
   Cần chạy trước: 01_cost_master.sql, 02_fn_cost_actual_daily.sql
   Fluctuating TGT + ngày làm việc lấy từ [F2Database].[dbo].[F2_Working_Date]
   EXEC [KVH].dbo.sp_CostMonitoringChart @MonthStart = '2026-10-01', @SimuWeek = NULL;
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

    ;WITH Cal AS (
        SELECT @MonthStart AS D
        UNION ALL SELECT DATEADD(DAY, 1, D) FROM Cal WHERE D < @MonthEnd
    ),
    WDay AS (                                   -- ngày làm việc + tỷ lệ Sales+Inven theo ngày (F2_Working_Date)
        -- GROUP BY ngày: bảng có >1 dòng / ngày thì Base bị nhân dòng, Act_MTD (cộng dồn) cộng 1 ngày nhiều lần
        -- -> lệch với tổng chi theo ngày. Mỗi ngày chỉ giữ 1 dòng.
        SELECT CAST(w.[Date] AS DATE) AS D,
               MAX(TRY_CONVERT(FLOAT, w.WD_All)) AS WD_All,
               MAX(TRY_CONVERT(FLOAT, w.Adj_Ratio_PR)) AS Adj_Ratio_PR,
               MAX(TRY_CONVERT(FLOAT, w.Adj_Ratio_MO)) AS Adj_Ratio_MO,
               MAX(TRY_CONVERT(FLOAT, w.Adj_Ratio_GU)) AS Adj_Ratio_GU
        FROM [F2Database].[dbo].[F2_Working_Date] w
        WHERE w.[Date] >= @MonthStart AND w.[Date] < DATEADD(DAY, 1, @MonthEnd)
        GROUP BY CAST(w.[Date] AS DATE)
    ),
    CalWD AS (                                  -- không có dòng trong F2_Working_Date thì coi là ngày làm việc
        SELECT c.D, ISNULL(w.WD_All, 1) AS WD
        FROM Cal c LEFT JOIN WDay w ON w.D = c.D
    ),
    Charts AS (
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
    LatestSimu AS (
        SELECT Div, Cate, KUsd,
               ROW_NUMBER() OVER (PARTITION BY Div, Cate ORDER BY DataType DESC) AS rn
        FROM Src
        WHERE DataType LIKE N'Simu[_]W%' AND ISNULL(KUsd, 0) <> 0
          AND (@SimuWeek IS NULL OR DataType = @SimuWeek)
    ),
    Tgt AS (
        SELECT c.Div, c.Chart,
               MAX(CASE WHEN s.DataType = N'FC' THEN s.KUsd END) AS FC_TGT,
               MAX(CASE WHEN s.DataType = N'BP' THEN s.KUsd END) AS BP,
               MAX(l.KUsd) AS PL_TGT
        FROM Charts c
        LEFT JOIN Src s        ON s.Div = c.TgtDiv AND s.Cate = c.Cate
        LEFT JOIN LatestSimu l ON l.Div = c.TgtDiv AND l.Cate = c.Cate AND l.rn = 1
        GROUP BY c.Div, c.Chart
    ),
    DailyAct AS (
        SELECT Div, Chart, D, SUM(Amt) AS Amt
        FROM dbo.fn_CostActualDaily(@MonthStart, @MonthEnd)
        GROUP BY Div, Chart, D
    ),
    Base AS (
        SELECT c.Div, c.Chart, k.D, t.FC_TGT, t.PL_TGT, t.BP,
               -- Fluctuating TGT = FC x (1 + Ratio); từ hôm nay trở đi giữ tỷ lệ của hôm qua (giống Excel cột S)
               t.FC_TGT * (1 + CASE c.Div WHEN N'PR' THEN wr.Adj_Ratio_PR
                                          WHEN N'MO' THEN wr.Adj_Ratio_MO
                                          ELSE wr.Adj_Ratio_GU END) AS Fluct_TGT,
               t.PL_TGT * SUM(k.WD) OVER (PARTITION BY c.Div, c.Chart ORDER BY k.D ROWS UNBOUNDED PRECEDING)
                        / NULLIF(SUM(k.WD) OVER (PARTITION BY c.Div, c.Chart), 0) AS PL_MTD,
               CASE WHEN c.Chart <> N'OT' AND k.D <= x.Cutoff THEN
                    SUM(CASE WHEN k.D <= x.Cutoff THEN ISNULL(a.Amt, 0) END)
                        OVER (PARTITION BY c.Div, c.Chart ORDER BY k.D ROWS UNBOUNDED PRECEDING)
               END AS Act_MTD
        FROM Charts c
        CROSS JOIN CalWD k
        CROSS APPLY (SELECT CASE WHEN c.Chart = N'RM' THEN @AsOfRM ELSE @AsOf END AS Cutoff) x
        JOIN Tgt t ON t.Div = c.Div AND t.Chart = c.Chart
        LEFT JOIN WDay wr ON wr.D = CASE WHEN k.D >= @Today THEN DATEADD(DAY, -1, @Today) ELSE k.D END
        LEFT JOIN DailyAct a ON a.Div = c.Div AND a.Chart = c.Chart AND a.D = k.D
    ),
    LastAct AS (
        SELECT Div, Chart, D AS LastD, Act_MTD AS LastAct, PL_MTD AS LastPL
        FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY Div, Chart ORDER BY D DESC) rn
              FROM Base WHERE Act_MTD IS NOT NULL) z
        WHERE rn = 1
    )
    SELECT b.Div, b.Chart, b.D,
           ROUND(b.FC_TGT, 3)  AS FC_TGT,
           ROUND(b.PL_TGT, 3)  AS PL_TGT,
           ROUND(b.BP, 3)      AS BP,
           ROUND(b.PL_MTD, 3)  AS PL_MTD,
           ROUND(b.Act_MTD, 3) AS Act_MTD,
           ROUND(CASE WHEN l.LastD IS NULL THEN b.PL_MTD
                      WHEN b.D > l.LastD  THEN l.LastAct + b.PL_MTD - l.LastPL END, 3) AS Est_MTD,
           ROUND(b.Fluct_TGT, 3) AS Fluct_TGT
    FROM Base b
    LEFT JOIN LastAct l ON l.Div = b.Div AND l.Chart = b.Chart
    ORDER BY b.Div, b.Chart, b.D
    OPTION (MAXRECURSION 40);
END
GO