/* =====================================================================
   Index cho Cost Monitoring (/api/cost-monitoring/chart, /detail, /detail/transactions, trợ lý AI)
   Chạy sau 02_fn_cost_actual_daily.sql (bản có điều kiện khoảng thô trên UsedDate).

   Truy vấn hiện tại lọc theo tháng:
     CostTool : UsedDate (yyyyMMdd) + Factory LIKE '612%' + GLAccount 570300/570400
     CostRm   : UsedDate (yyyyMMdd) + Factory = '612K'
     F2_PL_TGT: [Month] = '2026.10'
     F2_Working_Date: [Date] trong tháng
   -> index key UsedDate để seek đúng 1 tháng thay vì full scan cả bảng.

   Lưu ý (cần xác nhận trước khi chạy trên production):
   - Kiểm tra index đã có: EXEC sp_helpindex 'dbo.CostTool';
   - Nếu job import xóa / nạp lại toàn bộ bảng mỗi ngày, index làm job chậm hơn chút
     (thường không đáng kể so với lợi ích đọc).
   - F2_PL_TGT / F2_Working_Date thuộc F2Database: cần chủ DB đồng ý.
   - ONLINE = ON chỉ có ở bản Enterprise; bản Standard bỏ tùy chọn này (chạy ngoài giờ).
   ===================================================================== */
USE [KVH];
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_CostTool_UsedDate' AND object_id = OBJECT_ID(N'dbo.CostTool'))
    CREATE NONCLUSTERED INDEX IX_CostTool_UsedDate
        ON dbo.CostTool (UsedDate)
        INCLUDE (Factory, GLAccount, PLcostcentername, Amount);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_CostRm_UsedDate' AND object_id = OBJECT_ID(N'dbo.CostRm'))
    CREATE NONCLUSTERED INDEX IX_CostRm_UsedDate
        ON dbo.CostRm (UsedDate)
        INCLUDE (Factory, GLAccount, Movingtype, PLcostcentername, Code, [Text], Category, Amount);
GO

USE [F2Database];
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_F2_PL_TGT_Month' AND object_id = OBJECT_ID(N'dbo.F2_PL_TGT'))
    CREATE NONCLUSTERED INDEX IX_F2_PL_TGT_Month
        ON dbo.F2_PL_TGT ([Month])
        INCLUDE ([Div], [Cate], [DataType], [KUsd]);
GO

-- Bỏ qua nếu [Date] đã là khóa chính / clustered index
IF NOT EXISTS (
    SELECT 1
    FROM sys.index_columns ic
    JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
    WHERE ic.object_id = OBJECT_ID(N'dbo.F2_Working_Date') AND ic.key_ordinal = 1 AND c.name = N'Date'
)
    CREATE NONCLUSTERED INDEX IX_F2_Working_Date_Date
        ON dbo.F2_Working_Date ([Date])
        INCLUDE (WD_All, Adj_Ratio_PR, Adj_Ratio_MO, Adj_Ratio_GU);
GO

/* ---- Kiểm tra sau khi tạo ----
SET STATISTICS IO, TIME ON;
EXEC [KVH].dbo.sp_CostMonitoringChart @MonthStart = '2026-10-01', @SimuWeek = NULL;
-- logical reads của CostTool / CostRm phải giảm mạnh so với trước (không còn scan cả bảng)
*/
