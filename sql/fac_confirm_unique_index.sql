/*
 * F2_Backlog_Fac_Confirm: unique index (AUNFR, ProcessGrp)
 *
 * CHƯA CHẠY. Chạy theo thứ tự:
 *   Bước 1: kiểm tra bản ghi trùng (chỉ SELECT).
 *   Bước 2: dọn dữ liệu trùng (thủ công, sau khi nghiệp vụ duyệt; script này KHÔNG xóa dữ liệu).
 *   Bước 3: tạo unique index (tự dừng nếu vẫn còn trùng).
 *
 * Lưu ý: unique index nonclustered yêu cầu tổng độ dài key <= 1700 byte;
 * nếu AUNFR / ProcessGrp là (N)VARCHAR(MAX) thì bước 3 sẽ lỗi.
 */

USE [F2Database];
GO

-- =========================================================
-- BƯỚC 1: KIỂM TRA TRÙNG
-- =========================================================

-- Số cặp (AUNFR, ProcessGrp) bị trùng và tổng số bản ghi thừa
SELECT
    COUNT(*)                AS DuplicatePairs,
    ISNULL(SUM(Cnt - 1), 0) AS ExtraRows
FROM (
    SELECT AUNFR, ProcessGrp, COUNT(*) AS Cnt
    FROM dbo.F2_Backlog_Fac_Confirm
    GROUP BY AUNFR, ProcessGrp
    HAVING COUNT(*) > 1
) d;

-- Chi tiết các bản ghi trùng (bản ghi BE đang dùng = UpdatedAt mới nhất, rn = 1)
SELECT
    fc.*,
    ROW_NUMBER() OVER (
        PARTITION BY fc.AUNFR, fc.ProcessGrp
        ORDER BY fc.UpdatedAt DESC
    ) AS rn
FROM dbo.F2_Backlog_Fac_Confirm fc
WHERE EXISTS (
    SELECT 1
    FROM dbo.F2_Backlog_Fac_Confirm x
    WHERE x.AUNFR = fc.AUNFR
      AND x.ProcessGrp = fc.ProcessGrp
    GROUP BY x.AUNFR, x.ProcessGrp
    HAVING COUNT(*) > 1
)
ORDER BY fc.AUNFR, fc.ProcessGrp, rn;
GO

-- =========================================================
-- BƯỚC 3: TẠO UNIQUE INDEX
-- =========================================================

IF EXISTS (
    SELECT 1
    FROM dbo.F2_Backlog_Fac_Confirm
    GROUP BY AUNFR, ProcessGrp
    HAVING COUNT(*) > 1
)
BEGIN
    -- THROW chỉ dừng batch hiện tại, nên kiểm tra và tạo index nằm cùng một batch.
    THROW 50001, N'F2_Backlog_Fac_Confirm vẫn còn bản ghi trùng (AUNFR, ProcessGrp). Dọn dữ liệu trước khi tạo unique index.', 1;
END
ELSE IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE object_id = OBJECT_ID('dbo.F2_Backlog_Fac_Confirm')
      AND name = 'UX_F2_Backlog_Fac_Confirm_AUNFR_ProcessGrp'
)
BEGIN
    CREATE UNIQUE NONCLUSTERED INDEX UX_F2_Backlog_Fac_Confirm_AUNFR_ProcessGrp
        ON dbo.F2_Backlog_Fac_Confirm (AUNFR, ProcessGrp);
END;
GO
