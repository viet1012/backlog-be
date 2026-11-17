/* =========================================================
   Index đề xuất cho Backlog (/api/backlogs/search, /summary/status, /filter-options)

   dbo.F2_Backlog_Main là SYNONYM -> [dbo].[F2_Backlog_Batch_1] (kiểm 2026-11-17).
   Không tạo index trên synonym được (Msg 1914) -> tạo trên bảng gốc.

   Có 2 bảng nạp luân phiên: F2_Backlog_Batch_1, F2_Backlog_Batch_2 (+ F2_Backlog_Batch_Control).
   Script tạo index trên MỌI bảng dbo.F2_Backlog_Batch_<số> là USER_TABLE
   (synonym đổi sang batch nào cũng có index). Không đụng Batch_Control.
   Bảng là VIEW / không tồn tại -> bỏ qua, in thông báo.

   Trước khi chạy:
   1. Xem các bảng batch và index đã có:
        SELECT o.name, o.type_desc FROM sys.objects o WHERE o.name LIKE N'F2[_]Backlog[_]Batch[_]%';
        EXEC sp_helpindex 'dbo.F2_Backlog_Batch_1';
   2. Kiểm tra kiểu cột (VARCHAR hay NVARCHAR) và collation:
        SELECT name, TYPE_NAME(user_type_id) AS type, max_length, collation_name
        FROM sys.columns WHERE object_id = OBJECT_ID(N'dbo.F2_Backlog_Batch_1');
   3. Hỏi người phụ trách job nạp dữ liệu:
      - Job DROP + tạo lại bảng (SELECT INTO / CREATE TABLE) -> index mất sau mỗi lần nạp,
        phải thêm script này vào cuối job (trước khi đổi synonym).
      - Job TRUNCATE + INSERT -> index giữ nguyên; nạp chậm hơn chút, cân nhắc
        DISABLE index trước khi nạp và REBUILD sau khi nạp.
   ========================================================= */

DECLARE @table SYSNAME;
DECLARE @sql NVARCHAR(MAX);

DECLARE batch_tables CURSOR LOCAL FAST_FORWARD FOR
    SELECT t.name
    FROM sys.tables t
    WHERE t.schema_id = SCHEMA_ID(N'dbo')
      -- Chỉ Batch_<số> (Batch_1, Batch_2...), bỏ F2_Backlog_Batch_Control
      AND t.name LIKE N'F2[_]Backlog[_]Batch[_][0-9]%';

OPEN batch_tables;
FETCH NEXT FROM batch_tables INTO @table;

WHILE @@FETCH_STATUS = 0
BEGIN
    PRINT N'== ' + @table;

    -- Sort mặc định của /search: ORDER BY UpdatedAt DESC, VBELN DESC + OFFSET/FETCH.
    -- Không có index -> sort toàn bảng mỗi lần đổi trang; có index -> đọc N dòng đầu theo thứ tự.
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.' + @table) AND name = N'IX_' + @table + N'_UpdatedAt_VBELN')
    BEGIN
        SET @sql = N'CREATE NONCLUSTERED INDEX ' + QUOTENAME(N'IX_' + @table + N'_UpdatedAt_VBELN')
                 + N' ON dbo.' + QUOTENAME(@table) + N' (UpdatedAt DESC, VBELN DESC);';
        EXEC sys.sp_executesql @sql;
        PRINT N'   created IX_' + @table + N'_UpdatedAt_VBELN';
    END

    -- /summary/status: nhóm theo ExportD + Status, cộng FinalQty.
    -- Index hẹp phủ đủ cột -> quét index nhỏ thay vì cả bảng ~50 cột.
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.' + @table) AND name = N'IX_' + @table + N'_ExportD_Status')
    BEGIN
        SET @sql = N'CREATE NONCLUSTERED INDEX ' + QUOTENAME(N'IX_' + @table + N'_ExportD_Status')
                 + N' ON dbo.' + QUOTENAME(@table) + N' (ExportD, Status) INCLUDE (FinalQty);';
        EXEC sys.sp_executesql @sql;
        PRINT N'   created IX_' + @table + N'_ExportD_Status';
    END

    -- Lọc Excel theo Status (cột lọc phổ biến, cần xác nhận với FE / log thực tế).
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.' + @table) AND name = N'IX_' + @table + N'_Status')
    BEGIN
        SET @sql = N'CREATE NONCLUSTERED INDEX ' + QUOTENAME(N'IX_' + @table + N'_Status')
                 + N' ON dbo.' + QUOTENAME(@table) + N' (Status) INCLUDE (ExportD, FinalQty);';
        EXEC sys.sp_executesql @sql;
        PRINT N'   created IX_' + @table + N'_Status';
    END

    FETCH NEXT FROM batch_tables INTO @table;
END

CLOSE batch_tables;
DEALLOCATE batch_tables;

-- Không tìm thấy bảng nào -> có thể Batch_1 là VIEW
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE schema_id = SCHEMA_ID(N'dbo') AND name LIKE N'F2[_]Backlog[_]Batch[_][0-9]%')
    PRINT N'Không có USER_TABLE dbo.F2_Backlog_Batch_%. Kiểm tra: SELECT type_desc FROM sys.objects WHERE name = N''F2_Backlog_Batch_1'';';
GO
