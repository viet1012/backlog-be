/* =====================================================================
   Actual OT (tăng ca) theo ngày cho Cost Monitoring
   Nguồn: file "FY26 Daily Cost monitoning_FY26.xlsx" (\\192.168.0.6\dtp\9_Daily Costmonitoring\)
     sheet PressCOGS  -> SubDiv PR    (Excel Cost Monitoring: PR_OT!P    -> TOTAL!J)
     sheet MoldCOGS   -> SubDiv MO    (Mold_OT!P                         -> TOTAL!K)
     sheet PG+DP-COGS -> SubDiv PG    (Guide_OT!AB                       -> TOTAL!L)
     sheet MG-COGS    -> SubDiv MG    (Guide_OT!AC                       -> TOTAL!M)
     cột B = ngày, cột "Labor Cost / Actual / OT" (hiện là K) = OT thực tế, đơn vị K$.
   Nạp bằng API: POST /api/cost-monitoring/ot/import (upload file)
             hoặc POST /api/cost-monitoring/ot/import-from-share (BE tự đọc file trên share).
   Nạp lại = thay toàn bộ dòng của (tháng, SubDiv) có trong file.

   Thứ tự chạy: 07 (bảng) -> 02 (fn_CostActualDaily, có phần OT) -> 04 (sp_CostMonitoringChart).
   ===================================================================== */
USE [KVH];
GO

IF OBJECT_ID(N'dbo.CostOtDaily', N'U') IS NULL
CREATE TABLE dbo.CostOtDaily (
    D         DATE           NOT NULL,
    SubDiv    NVARCHAR(10)   NOT NULL,   -- PR | MO | PG | MG  (Guide = PG + MG)
    AmtKUsd   DECIMAL(18, 6) NOT NULL,   -- K$ đúng như file nguồn (KHÔNG chia 1000); giữ cả số âm
    Source    NVARCHAR(260)  NULL,       -- tên file upload hoặc 'share:<đường dẫn>'
    LoadedBy  NVARCHAR(20)   NULL,       -- mã nhân viên (X-Employee-Id); NULL = job tự động
    LoadedAt  DATETIME2(0)   NOT NULL CONSTRAINT DF_CostOtDaily_LoadedAt DEFAULT SYSDATETIME(),
    CONSTRAINT PK_CostOtDaily PRIMARY KEY CLUSTERED (D, SubDiv),
    CONSTRAINT CK_CostOtDaily_SubDiv CHECK (SubDiv IN (N'PR', N'MO', N'PG', N'MG'))
);
GO

/* ---- Kiểm tra sau khi nạp ----
SELECT SubDiv, MIN(D) AS FromD, MAX(D) AS ToD, COUNT(*) AS Days, SUM(AmtKUsd) AS TotalK, MAX(LoadedAt) AS LoadedAt
FROM dbo.CostOtDaily
WHERE D >= DATEFROMPARTS(YEAR(GETDATE()), MONTH(GETDATE()), 1)
GROUP BY SubDiv;
-- Tháng 11/2026 (file mẫu): PR ≈ 27.16, MO ≈ 7.43, PG ≈ 9.70, MG ≈ 2.43 K$ (dòng "Total" cột K của từng sheet)
*/
