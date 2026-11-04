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
Tsl AS (
    SELECT u.UD AS D,
           x.CC,
           SUM(ISNULL(TRY_CONVERT(FLOAT, REPLACE(t.Amount, ',', '')), 0)) AS Amt
    FROM dbo.CostTool t
    CROSS APPLY (SELECT TRY_CONVERT(DATE, CONVERT(VARCHAR(8), t.UsedDate), 112) AS UD) u
    LEFT JOIN dbo.CostCenterMaster m ON m.PLCostCenterName = LTRIM(RTRIM(t.PLcostcentername))
    CROSS APPLY (SELECT CASE WHEN NULLIF(LTRIM(RTRIM(t.PLcostcentername)), '') IS NULL THEN N'F2'   -- Excel: tên trống -> F2
                             ELSE ISNULL(m.CostCenter, N'No master') END AS CC) x
    WHERE LEFT(t.Factory, 3) = '612'                                   -- 612K / 612I
      AND TRY_CONVERT(INT, t.GLAccount) IN (570300, 570400)
      AND u.UD BETWEEN @From AND @To
    GROUP BY u.UD, x.CC
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
TslDiv AS (
    SELECT p.D, N'PR' AS Div,
           (p.PR + p.HT2 * r.HT2_PR + p.F2 * r.F2_PR + p.HTG * r.HTG_PR) / 1000 AS Amt
    FROM TslP p CROSS JOIN R r
    UNION ALL
    SELECT p.D, N'MO',
           (p.MO + p.HT2 * (1 - r.HT2_PR - r.HT2_GU) + p.F2 * (1 - r.F2_PR - r.F2_GU)
                 + p.HTG * (1 - r.HTG_PR - r.HTG_GU)) / 1000
    FROM TslP p CROSS JOIN R r
    UNION ALL
    SELECT p.D, N'Guide',
           (p.GU + p.HT2 * r.HT2_GU + p.F2 * r.F2_GU + p.HTG * r.HTG_GU) / 1000
    FROM TslP p CROSS JOIN R r
),
/* ---------- RM  (sheet Detail_RMused / Sum RM) ---------- */
Rm AS (
    SELECT u.UD AS D,
           CASE WHEN r.[Text] LIKE N'%CAC%' AND r.Category LIKE N'%MO%' THEN N'614200'
                WHEN NULLIF(LTRIM(RTRIM(r.PLcostcentername)), '') IS NULL THEN N'F2'         -- Excel: tên trống -> F2 (không tính)
                ELSE COALESCE(m.CostCenter, c.CostCenter, N'614100')
           END AS CC,
           CASE WHEN TRY_CONVERT(INT, r.GLAccount) = 580000
                  OR TRY_CONVERT(INT, r.Movingtype) IN (551, 552) THEN 0
                ELSE ISNULL(TRY_CONVERT(FLOAT, REPLACE(r.Amount, ',', '')), 0)
           END AS Amt
    FROM dbo.CostRm r
    CROSS APPLY (SELECT TRY_CONVERT(DATE, CONVERT(VARCHAR(8), r.UsedDate), 112) AS UD) u
    LEFT JOIN dbo.CostCenterMaster m ON m.PLCostCenterName = LTRIM(RTRIM(r.PLcostcentername))
    LEFT JOIN dbo.CostCodeMaster   c ON c.Code = LTRIM(RTRIM(r.Code))
    WHERE r.Factory = '612K'
      AND u.UD BETWEEN @From AND @To
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
