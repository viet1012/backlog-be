/* =====================================================================
   Actual theo ngày (K$) cho T&SL và RM — theo logic file Excel
   Kết quả: (D, Div, Chart, Amt)   Div = PR / MO / Guide, Chart = T&SL / RM
   ===================================================================== */
USE [KVH];
GO
CREATE OR ALTER FUNCTION dbo.fn_CostActualDaily (@From DATE, @To DATE)
RETURNS TABLE
AS
RETURN
WITH
/* ---------- T&SL  (sheet TTL / SumT&SL) ---------- */
/* Hiệu năng: gom theo (ngày, tên cost center) TRƯỚC rồi mới join master -> join vài trăm dòng thay vì
   tra CostCenterMaster cho từng dòng xuất kho (trước đây ~41k logical reads). */
TslRaw AS (
    SELECT u.UD AS D,
           LTRIM(RTRIM(t.PLcostcentername)) AS Nm,
           SUM(ISNULL(TRY_CONVERT(FLOAT, REPLACE(t.Amount, ',', '')), 0)) AS Amt
    FROM dbo.CostTool t
    CROSS APPLY (SELECT TRY_CONVERT(DATE, CONVERT(VARCHAR(8), t.UsedDate), 112) AS UD) u
    WHERE t.Factory LIKE '612%'                                        -- 612K / 612I (sargable, = LEFT(Factory,3)='612')
      AND TRY_CONVERT(INT, t.GLAccount) IN (570300, 570400)
      -- Khoảng thô trên cột gốc để seek index IX_CostTool_UsedDate (UsedDate dạng yyyyMMdd, số hoặc chuỗi);
      -- điều kiện chính xác vẫn là u.UD bên dưới.
      AND t.UsedDate >= CONVERT(CHAR(8), @From, 112)
      AND t.UsedDate <  CONVERT(CHAR(8), DATEADD(DAY, 1, @To), 112)
      AND u.UD BETWEEN @From AND @To
    GROUP BY u.UD, LTRIM(RTRIM(t.PLcostcentername))
),
Tsl AS (
    SELECT r.D,
           CASE WHEN NULLIF(r.Nm, '') IS NULL THEN N'F2'                   -- Excel: tên trống -> F2
                ELSE ISNULL(m.CostCenter, N'No master') END AS CC,
           r.Amt
    FROM TslRaw r
    LEFT JOIN dbo.CostCenterMaster m ON m.PLCostCenterName = r.Nm
),
TslP AS (
    SELECT D,
           SUM(CASE WHEN CC = N'614100' THEN Amt ELSE 0 END) AS PR,
           SUM(CASE WHEN CC = N'614200' THEN Amt ELSE 0 END) AS MO,
           SUM(CASE WHEN CC IN (N'614600', N'614700') THEN Amt ELSE 0 END) AS GU,
           SUM(CASE WHEN CC = N'HT2'  THEN Amt ELSE 0 END) AS HT2,
           SUM(CASE WHEN CC = N'F2'   THEN Amt ELSE 0 END) AS F2,
           SUM(CASE WHEN CC = N'HT-G' THEN Amt ELSE 0 END) AS HTG
    FROM Tsl
    GROUP BY D
),
/* Tỷ lệ phân bổ chi phí chung (TTL!F2:J4) — đang hardcode trong Excel, sửa khi Excel đổi */
R AS (
    SELECT 0.761231348566323 + 0.00141591062565421 AS HT2_PR, 0.0   AS HT2_GU,   -- HT2  (Press + FA / Guide)
           0.472609520853777                       AS F2_PR,  0.283 AS F2_GU,    -- F2
           0.0                                     AS HTG_PR, 1.0   AS HTG_GU    -- HT-G
),
TslDiv AS (                                  -- 1 lần đọc TslP (trước: UNION ALL 3 lần -> CostTool scan 3 lần)
    SELECT p.D, v.Div, v.Amt
    FROM TslP p
    CROSS JOIN R r
    CROSS APPLY (VALUES
        (N'PR',    (p.PR + p.HT2 * r.HT2_PR + p.F2 * r.F2_PR + p.HTG * r.HTG_PR) / 1000),
        (N'MO',    (p.MO + p.HT2 * (1 - r.HT2_PR - r.HT2_GU) + p.F2 * (1 - r.F2_PR - r.F2_GU)
                         + p.HTG * (1 - r.HTG_PR - r.HTG_GU)) / 1000),
        (N'Guide', (p.GU + p.HT2 * r.HT2_GU + p.F2 * r.F2_GU + p.HTG * r.HTG_GU) / 1000)
    ) v(Div, Amt)
),
/* ---------- RM  (sheet Detail_RMused / Sum RM) ---------- */
/* Gom theo (ngày, CAC+MO, tên cost center, mã) trước rồi mới join master (như T&SL) */
RmRaw AS (
    SELECT u.UD AS D,
           v.IsCacMo,
           LTRIM(RTRIM(r.PLcostcentername)) AS Nm,
           LTRIM(RTRIM(r.Code))             AS Cd,
           SUM(CASE WHEN TRY_CONVERT(INT, r.GLAccount) = 580000
                      OR TRY_CONVERT(INT, r.Movingtype) IN (551, 552) THEN 0
                    ELSE ISNULL(TRY_CONVERT(FLOAT, REPLACE(r.Amount, ',', '')), 0)
               END) AS Amt
    FROM dbo.CostRm r
    CROSS APPLY (SELECT TRY_CONVERT(DATE, CONVERT(VARCHAR(8), r.UsedDate), 112) AS UD) u
    CROSS APPLY (SELECT CASE WHEN r.[Text] LIKE N'%CAC%' AND r.Category LIKE N'%MO%' THEN 1 ELSE 0 END AS IsCacMo) v
    WHERE r.Factory = '612K'
      -- Khoảng thô để seek index IX_CostRm_UsedDate (xem phần T&SL)
      AND r.UsedDate >= CONVERT(CHAR(8), @From, 112)
      AND r.UsedDate <  CONVERT(CHAR(8), DATEADD(DAY, 1, @To), 112)
      AND u.UD BETWEEN @From AND @To
    GROUP BY u.UD, v.IsCacMo, LTRIM(RTRIM(r.PLcostcentername)), LTRIM(RTRIM(r.Code))
),
Rm AS (
    SELECT x.D,
           CASE WHEN x.IsCacMo = 1 THEN N'614200'
                WHEN NULLIF(x.Nm, '') IS NULL THEN N'F2'                   -- Excel: tên trống -> F2 (không tính)
                ELSE COALESCE(m.CostCenter, c.CostCenter, N'614100')
           END AS CC,
           x.Amt
    FROM RmRaw x
    LEFT JOIN dbo.CostCenterMaster m ON m.PLCostCenterName = x.Nm
    LEFT JOIN dbo.CostCodeMaster   c ON c.Code = x.Cd
),
RmDiv AS (
    SELECT D,
           CASE CC WHEN N'614100' THEN N'PR'
                   WHEN N'614200' THEN N'MO'
                   ELSE N'Guide' END AS Div,                         -- 614600 + 614700
           SUM(Amt) / 1000 AS Amt
    FROM Rm
    WHERE CC IN (N'614100', N'614200', N'614600', N'614700')
    GROUP BY D, CASE CC WHEN N'614100' THEN N'PR' WHEN N'614200' THEN N'MO' ELSE N'Guide' END
)
SELECT D, Div, CAST(N'T&SL' AS NVARCHAR(10)) AS Chart, Amt FROM TslDiv
UNION ALL
SELECT D, Div, CAST(N'RM'   AS NVARCHAR(10)) AS Chart, Amt FROM RmDiv;
GO

/* ---- Test nhanh: actual theo ngày + MTD của tháng hiện tại ---- */
DECLARE @From DATE = DATEFROMPARTS(YEAR(GETDATE()), MONTH(GETDATE()), 1);
DECLARE @To   DATE = DATEADD(DAY, -1, CAST(GETDATE() AS DATE));

SELECT Div, Chart, D, Amt AS Act_Day,
       SUM(Amt) OVER (PARTITION BY Div, Chart ORDER BY D ROWS UNBOUNDED PRECEDING) AS Act_MTD
FROM dbo.fn_CostActualDaily(@From, @To)
ORDER BY Div, Chart, D;
