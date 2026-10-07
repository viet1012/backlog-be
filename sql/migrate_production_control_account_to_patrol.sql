/*
 * Migrate một lần: F2_ProductionControl_Account -> HSE_Patrol_Account
 *
 * - Chỉ copy EmployeeId CHƯA có trong HSE_Patrol_Account
 *   (so khớp UPPER(LTRIM(RTRIM(Account)))). Account đã có thì bỏ qua, không ghi đè Pass.
 * - Mỗi EmployeeId chỉ lấy 1 dòng (Id lớn nhất) phòng trường hợp bảng cũ bị trùng.
 * - Bỏ qua dòng có PasswordHash NULL / rỗng (không có mật khẩu để copy).
 * - Bỏ qua dòng có PasswordHash dạng BCrypt ($2a$/$2b$/$2y$): login giờ so sánh plain text
 *   nên các mật khẩu này không dùng được. Bước 1 liệt kê để xử lý riêng.
 *
 * Chạy bước 1 (preview) trước, kiểm tra kết quả rồi mới chạy bước 2.
 */

USE [F2Database];
GO

-- =========================================================
-- BƯỚC 1: PREVIEW
-- =========================================================

;WITH src AS (
    SELECT
        UPPER(LTRIM(RTRIM(a.EmployeeId))) AS EmployeeId,
        a.PasswordHash,
        a.CreatedAt,
        a.LastLoginAt,
        ROW_NUMBER() OVER (
            PARTITION BY UPPER(LTRIM(RTRIM(a.EmployeeId)))
            ORDER BY a.Id DESC
        ) AS rn
    FROM dbo.F2_ProductionControl_Account a
    WHERE a.EmployeeId IS NOT NULL
      AND LTRIM(RTRIM(a.EmployeeId)) <> ''
)
SELECT
    s.EmployeeId,
    s.CreatedAt,
    s.LastLoginAt,
    CASE
        WHEN p.Account IS NOT NULL THEN 'SKIP - already in HSE_Patrol_Account'
        WHEN s.PasswordHash IS NULL OR LTRIM(RTRIM(s.PasswordHash)) = '' THEN 'SKIP - no password'
        WHEN s.PasswordHash LIKE '$2[aby]$%' THEN 'SKIP - BCrypt hash, handle manually'
        ELSE 'INSERT'
    END AS Action
FROM src s
OUTER APPLY (
    SELECT TOP 1 h.Account
    FROM dbo.HSE_Patrol_Account h
    WHERE UPPER(LTRIM(RTRIM(h.Account))) = s.EmployeeId
) p
WHERE s.rn = 1
ORDER BY Action, s.EmployeeId;
GO

-- =========================================================
-- BƯỚC 2: MIGRATE
-- =========================================================

SET XACT_ABORT ON;
BEGIN TRANSACTION;

;WITH src AS (
    SELECT
        UPPER(LTRIM(RTRIM(a.EmployeeId))) AS EmployeeId,
        a.PasswordHash,
        a.CreatedAt,
        a.LastLoginAt,
        ROW_NUMBER() OVER (
            PARTITION BY UPPER(LTRIM(RTRIM(a.EmployeeId)))
            ORDER BY a.Id DESC
        ) AS rn
    FROM dbo.F2_ProductionControl_Account a
    WHERE a.EmployeeId IS NOT NULL
      AND LTRIM(RTRIM(a.EmployeeId)) <> ''
)
INSERT INTO dbo.HSE_Patrol_Account
(
    Account,
    Pass,
    NewDT,
    UpdDT,
    Last_Login
)
SELECT
    s.EmployeeId,
    s.PasswordHash,
    s.CreatedAt,
    SYSDATETIME(),
    s.LastLoginAt
FROM src s
WHERE s.rn = 1
  AND s.PasswordHash IS NOT NULL
  AND LTRIM(RTRIM(s.PasswordHash)) <> ''
  AND s.PasswordHash NOT LIKE '$2[aby]$%'
  AND NOT EXISTS (
      SELECT 1
      FROM dbo.HSE_Patrol_Account h
      WHERE UPPER(LTRIM(RTRIM(h.Account))) = s.EmployeeId
  );

SELECT @@ROWCOUNT AS InsertedRows;

-- Kiểm tra số dòng InsertedRows khớp với preview rồi mới COMMIT.
-- COMMIT TRANSACTION;
-- ROLLBACK TRANSACTION;
