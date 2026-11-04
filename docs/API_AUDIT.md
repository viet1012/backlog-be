# Báo cáo audit API: backlog-be

| | |
|---|---|
| Ngày audit | 2026-11-03 |
| Phạm vi | 29 endpoint / 8 controller (Auth, Backlog, Shipment Fulfillment, ODBF, FAC Confirm, Machine Locator, Machine Monitor) |
| Phương pháp | Đọc toàn bộ controller → service → repository (SQL). Chạy `mvnw test`. Không chạy trên DB thật |
| Kết quả test | `mvnw test`: BUILD SUCCESS, 0 failure. Chỉ module FAC Confirm có test |

Mức độ: **Cao**: lỗi chức năng hoặc rủi ro bảo mật cần xử lý sớm. **Trung bình**: sai lệch dữ liệu, hiệu năng, độ ổn định. **Thấp**: nhất quán, tài liệu, code thừa.
Mục ghi *(cần xác nhận)* là suy luận từ code, cần kiểm chứng với dữ liệu hoặc nghiệp vụ thật.

## 1. Tóm tắt

| Mức | Số lượng | Nổi bật |
|---|---|---|
| Cao | 7 | API `/shipment-fulfillment/detail` hỏng; không có xác thực thật; mật khẩu plain text; credential DB trong git; export Excel rò file tạm / timeout |
| Trung bình | 15 | Giới hạn 2100 tham số SQL Server; ODBF đếm trùng; tràn số `page*size`; định dạng lỗi không thống nhất; truy vấn không giới hạn |
| Thấp | 14 | Múi giờ, escape `LIKE`, code thừa, README cũ sai |

**Điểm tốt:**
- **Không phát hiện SQL injection.** Tên cột đi qua whitelist hoặc metadata và được bọc `[...]`. Operator và `logicOperator` là `switch` cố định. Mọi giá trị đều bind tham số.
- Optimistic lock của Machine Locator đúng và atomic (`UPDLOCK, HOLDLOCK` trong transaction).
- FAC Confirm validate đầy đủ, ghi trong transaction, và đếm dòng trước khi stream Excel.

## 2. Phát hiện mức Cao

### H1. `GET /api/shipment-fulfillment/detail` luôn lỗi 500 khi có dữ liệu
- **Vị trí:** [ShipmentDetailRepository.java:23-69](../src/main/java/com/example/backlogbe/repository/ShipmentDetailRepository.java#L23-L69) và [BacklogRowMapper.java:114-133](../src/main/java/com/example/backlogbe/repository/backlog/BacklogRowMapper.java#L114-L133).
- **Nguyên nhân:** `SELECT_COLUMNS` thiếu 9 cột mà `BacklogRowMapper` đọc: `Classify`, `ProcessGrp2`, `ProductGrp`, `CountODBF`, `Status2`, `Pickup_Time`, `PK_Received`, `WaitingDays`, `Heat_Note`. Mapper được thêm cột mới nhưng repository này không được cập nhật theo.
- **Hậu quả:** mọi truy vấn có ít nhất 1 dòng gây `SQLServerException: The column name Classify is not valid` → 500. Truy vấn rỗng thì vẫn trả `[]`, nên lỗi dễ bị bỏ sót khi test.
- **Đề xuất:** thêm 9 cột vào `SELECT_COLUMNS`, hoặc dùng chung danh sách cột với `BacklogMainRepository`.

### H2. Không có xác thực thật: danh tính do client tự khai
- **Vị trí:** [MachineLocatorService.java:656-687](../src/main/java/com/example/backlogbe/service/MachineLocatorService.java#L656-L687), [FacConfirmProcessTimeService.java:57-60](../src/main/java/com/example/backlogbe/service/FacConfirmProcessTimeService.java#L57-L60), [ProductionControlAuthService.java:163](../src/main/java/com/example/backlogbe/service/auth/ProductionControlAuthService.java#L163).
- **Nguyên nhân:** login không cấp token hay session. Machine Locator tin header `X-Employee-Id`. FAC Confirm tin `employeeId` trong body.
- **Kịch bản:** ai biết mã nhân viên của một người thuộc PE IT (mã nhân viên không phải bí mật) có thể dùng `curl -H "X-Employee-Id: <mã>"` để sửa, xóa hoặc import bản đồ máy của mọi tầng. Mọi người đều có thể ghi xác nhận FAC dưới tên người khác.
- **Yếu tố khuếch đại:** `@CrossOrigin("*")` trên mọi controller. Một trang web bất kỳ mà nhân viên mở trên mạng nội bộ cũng gửi được request ghi.
- **Đề xuất:** cấp JWT hoặc session khi login và xác minh ở server (Spring Security). Giới hạn CORS về domain của FE.

### H3. `/register` cho phép chiếm tài khoản của nhân viên chưa đăng ký
- **Vị trí:** [ProductionControlAuthService.java:37-101](../src/main/java/com/example/backlogbe/service/auth/ProductionControlAuthService.java#L37-L101).
- **Kịch bản:** chỉ cần mã nhân viên có trong `F2_HR_Data` và chưa có tài khoản là đăng ký được, không cần chứng minh danh tính. Tài khoản tạo ra nằm trong `HSE_Patrol_Account`, **dùng chung với S-Patrol**, nên ảnh hưởng cả hệ thống khác. Nếu đăng ký mã của một người PE IT, tài khoản đó có quyền ADMIN ngay.
- **Đề xuất:** đăng ký cần xác minh (OTP qua email công ty, hoặc admin duyệt). Cân nhắc tắt `/register` nếu tài khoản đã được tạo từ S-Patrol.

### H4. Mật khẩu lưu và so sánh dạng plain text
- **Vị trí:** [ProductionControlAuthService.java:130](../src/main/java/com/example/backlogbe/service/auth/ProductionControlAuthService.java#L130), [ProductionControlAuthRepository.java:112-139](../src/main/java/com/example/backlogbe/repository/auth/ProductionControlAuthRepository.java#L112-L139).
- **Nguyên nhân:** `password.equals(pass)`. Bean `BCryptPasswordEncoder` ([PasswordConfig.java](../src/main/java/com/example/backlogbe/config/PasswordConfig.java)) có khai báo nhưng không dùng. Script migration ghi rõ việc chuyển từ BCrypt sang plain text là có chủ ý, để dùng chung với S-Patrol.
- **Rủi ro:** ai đọc được DB là có mật khẩu của toàn bộ người dùng. Không có giới hạn số lần đăng nhập sai (brute force).
- **Đề xuất:** thống nhất với team S-Patrol để chuyển sang hash (BCrypt hoặc Argon2), và thêm rate limit cho `/login`. Đây là thay đổi ảnh hưởng hệ thống khác, cần xác nhận với team đó trước.

### H5. Thông tin đăng nhập DB nằm trong git
- **Vị trí:** [application.properties:4-6](../src/main/resources/application.properties#L4-L6), có trong lịch sử commit từ commit `init`.
- **Đề xuất:** đổi mật khẩu DB (password hiện tại coi như đã lộ), chuyển sang biến môi trường hoặc secret store (README mục 2.2). Dùng tài khoản DB có quyền tối thiểu cho ứng dụng thay vì tài khoản admin.

### H6. Export Backlog không xóa file tạm của POI
- **Vị trí:** [BacklogExcelService.java:643-646](../src/main/java/com/example/backlogbe/service/BacklogExcelService.java#L643-L646).
- **Nguyên nhân:** `SXSSFWorkbook.close()` không gọi `dispose()`, nên mỗi lần export (kể cả lỗi) để lại file `poi-sxssf-sheet*.gz` trong `java.io.tmpdir`. Với export lớn, file này có thể tới hàng trăm MB.
- **Đề xuất:** gọi `workbook.dispose()` trong `finally`. Kiểm tra `FacConfirmExcelService` cùng cách.

### H7. Export Excel lớn có thể bị cắt bởi async timeout *(cần xác nhận)*
- **Vị trí:** [BacklogMainController.java:198-203](../src/main/java/com/example/backlogbe/controller/BacklogMainController.java#L198-L203), [FacConfirmController.java:324-329](../src/main/java/com/example/backlogbe/controller/FacConfirmController.java#L324-L329).
- **Nguyên nhân:** `StreamingResponseBody` chạy async. Chưa cấu hình `spring.mvc.async.request-timeout`, nên dùng mặc định của Tomcat (~30 giây). Với Backlog, client không nhận byte nào cho tới khi workbook ghi xong.
- **Hậu quả:** export trên 30 giây trả 503, trong khi thread vẫn giữ 1 trong 10 kết nối Hikari tới khi chạy xong.
- **Đề xuất:** đặt `spring.mvc.async.request-timeout=10m` và giới hạn số export chạy đồng thời. Kiểm chứng bằng một lần export lớn.

## 3. Phát hiện mức Trung bình

| # | Module | Vị trí | Vấn đề / kịch bản | Đề xuất |
|---|---|---|---|---|
| M1 | Backlog, FAC Confirm | `BacklogFilterSqlBuilder.java:161-222`, `FacConfirmProcessTimeRepository.java:42-48`, `FacConfirmFilterSqlBuilder` | Danh sách `values[]` của filter `in` và body `/confirmed-processes` không giới hạn. SQL Server cho phép tối đa 2100 tham số, filter DATE dùng 2 tham số mỗi giá trị. Chọn "tất cả trừ một" trên cột nhiều giá trị → 500 | Giới hạn kích thước (trả 400), hoặc truyền JSON + `OPENJSON` |
| M2 | ODBF | `OdbfRepository.java:106-108` | `LEFT JOIN F2_Backlog_Fac_Confirm` không lấy bản ghi mới nhất. Dữ liệu cũ trùng (`AUNFR`, `ProcessGrp`) (README mục 2.5) làm nhân đôi `countPo`/`sumQty` và sai tỷ lệ | Dùng `ROW_NUMBER() ... rn = 1` như `LatestConfirm` của FAC Confirm, và chạy script unique index |
| M3 | ODBF | `OdbfRepository.java:66` | `CountODBF <> 'No Count'` loại luôn dòng `CountODBF IS NULL` (NULL so sánh ra UNKNOWN) *(cần xác nhận nghiệp vụ)* | `ISNULL(CountODBF, '') <> 'No Count'` nếu NULL cần được tính |
| M4 | ODBF | `OdbfRepository.java` | Không tham số, quét toàn bộ `F2_Backlog_Main` mỗi lần gọi, trả mọi ngày từ trước tới nay. Div `PR` hard-code. Có `@EnableCaching` nhưng không có `@Cacheable` nào | Thêm khoảng ngày (`from`/`to`) và `div`, hoặc cache có TTL |
| M5 | Backlog, FAC Confirm | `BacklogMainRepository.java:138-139`, `FacConfirmRepository.java:587,752` | `page * size` tính bằng `int` bị tràn số, ví dụ `page=10737419&size=200` → OFFSET âm → 500 | Tính bằng `long` và giới hạn `page` |
| M6 | Chung | `GlobalExceptionHandler.java:25-35` | Mọi `IllegalStateException` trên toàn ứng dụng thành **403 Forbidden**, kể cả lỗi vượt giới hạn dòng khi export Backlog | Map thành 500/409; dùng exception riêng cho trường hợp 403 |
| M7 | Chung | `GlobalExceptionHandler`, `BacklogMainController.java:151-158`, `MachineMonitorService` | Có 3 định dạng lỗi khác nhau. Lỗi export Backlog và Machine Monitor dùng định dạng mặc định của Spring, **mất message**. `Map.of("message", null)` gây NPE khi exception không có message | Một `@RestControllerAdvice` chung trả `{status, message}`, xử lý cả `ResponseStatusException`, `DataAccessException`, lỗi tham số |
| M8 | Backlog | `BacklogExcelService.java:701-711` | Giới hạn 1.048.570 dòng chỉ được phát hiện **sau khi** đã đọc khoảng 1 triệu dòng | Đếm trước khi stream, như FAC Confirm đã làm |
| M9 | Backlog | `BacklogMainRepository.java:536-562` | `/filter-options` chỉ áp `limit` cho 9 cột. Cột text tự do (`PRT_ADDCMT1/2`, `Heat_Note`, `Status2`) trả mọi giá trị distinct | Luôn dùng `TOP (limit+1)` và trả cờ `truncated` |
| M10 | Backlog | `BacklogMainRepository.java:686-692` | Sort theo một cột không có khóa phụ duy nhất, nên với OFFSET/FETCH dòng có thể lặp hoặc mất giữa các trang | Luôn thêm `AUFNR` (hoặc khóa duy nhất) vào cuối `ORDER BY` |
| M11 | Backlog | `BacklogMainController.java:103-121` | `/summary/status` không nhận `search`, nên khi người dùng gõ tìm kiếm, bảng và ma trận tổng hợp lệch nhau. `findExportStatusSummary` (có hỗ trợ search) không được gọi | Nhận `search` và dùng chung query builder |
| M12 | Backlog | `BacklogMainRepository.java:100-127` | Search dùng 9 điều kiện `CAST(...) LIKE '%kw%'`, cộng thêm `COUNT_BIG`, nên quét toàn bảng mỗi lần gõ phím | Yêu cầu độ dài tối thiểu, debounce ở FE, cân nhắc full-text index |
| M13 | FAC Confirm | `ClientMachineService.java:63-151`, `FacConfirmController.java:365-393` | Tin `X-Forwarded-For` không qua proxy tin cậy, nên cột `Updater` giả mạo được (`X-Forwarded-For: BOSS-PC` → `Updater = BOSS-PC_...`). Reverse DNS chạy đồng bộ, không timeout | `server.forward-headers-strategy` + danh sách proxy tin cậy; resolve DNS có timeout/cache hoặc lưu IP |
| M14 | FAC Confirm | `FacConfirmProcessTimeService.java:361-369` | Lưu được xác nhận cho PO **không tồn tại** trong `F2_Backlog_Main`, tạo bản ghi mồ côi | Từ chối AUFNR không có trong kết quả `findEditStates` |
| M15 | Machine Locator / Monitor | `MachineLocatorService.java:522-526, 396-454, 862-873`; `MachineMonitorRepository.java:97-135` | Import không bắt buộc `ifVersion`, nên có thể ghi đè thay đổi của người khác. Không giới hạn số `ops`, số máy, số điểm polygon; nội dung polygon không được kiểm tra. Monitor đọc toàn bộ lịch sử patrol, lọc SAFE trong Java | Bắt buộc `ifVersion` (hoặc `force=true`). Đặt giới hạn kích thước. Đưa điều kiện ngày vào SQL |

## 4. Phát hiện mức Thấp

| # | Vị trí | Vấn đề | Đề xuất |
|---|---|---|---|
| L1 | `/confirmed-processes` (`FacConfirmProcessTimeRepository.java:50-105`) | Không lấy "bản ghi mới nhất", nên trả nhiều dòng cho một ô nếu dữ liệu cũ trùng | Dùng CTE `LatestConfirm` |
| L2 | `FacConfirmController.getClientIp` vs `ClientMachineService.getClientIp` | Hai bản code khác nhau, nên `/debug-client` có thể báo IP khác với IP thật khi lưu. Header `X-Forwarded-For: ","` gây lỗi 500 | Xóa bản trong controller, gọi service |
| L3 | `FacConfirmProcessTimeService` | Validate và ghi dưới READ COMMITTED, nên hai lần lưu đồng thời có thể cùng qua kiểm tra thứ tự thời gian | `UPDLOCK` khi đọc trạng thái |
| L4 | `MachineMonitorService.java:130,345`, Excel file name | Dùng múi giờ JVM. Server chạy UTC thì OVERDUE và tên file lệch 7 giờ *(cần xác nhận)* | `ZoneId.of("Asia/Ho_Chi_Minh")` |
| L5 | `MachineMonitorService.java:331-339` | `hse_judge = NG` nhưng `at_status = Pro_Done` hiển thị `WAIT_HSE` *(cần xác nhận quy trình S-Patrol)* | Map `hse_judge` khác OK thành NG |
| L6 | `ProductionControlAuthService.java:231-233, 243-256` | So khớp role bằng `contains` (`PE IT`, `PC`, `PRO`) nên dễ khớp nhầm, ví dụ "PROCUREMENT" → `PRO` | So khớp theo token chính xác |
| L7 | `BacklogFilterSqlBuilder`, `FacConfirmFilterSqlBuilder` | Không escape `%`, `_`, `[` trong `LIKE` (ví dụ `A_1` khớp `AB1`) | Escape và thêm `ESCAPE '\'` |
| L8 | `SmartDateTimeSerializer` | Định dạng ngày đầu ra thay đổi theo từng giá trị, không phải ISO, khác với định dạng đầu vào | Thống nhất ISO-8601, để FE format |
| L9 | `BacklogFilterSqlBuilder.java:866-879` | ISO datetime có múi giờ (`...Z`) bị cắt về ngày UTC, nên lọc lệch một ngày *(phụ thuộc FE)* | Từ chối offset hoặc quy đổi theo `ZoneId` |
| L10 | `/summary/status` | `totalPoCount` tính cả dòng không có `ExportD`, ma trận thì không, nên tổng các ô không khớp tổng | Thêm cột "(Không ngày)" hoặc ghi chú trên UI |
| L11 | `ShipmentFulfillmentController` | `fromD`/`toD` không kiểm tra `fromD <= toD` và không giới hạn khoảng. `/detail` không phân trang | Validate và giới hạn |
| L12 | `BacklogColumnMetadataProvider` | Đọc metadata lúc khởi động: DB không kết nối được thì app không lên, đổi schema phải restart | Load lazy, có retry |
| L13 | Code thừa | `BacklogStatusSummaryItemDto`, `BacklogStatusDateRowDto`, `BacklogStatusDateCellDto`, `findExportStatusSummary`, `FacConfirmProcessTimeResponse`, `FacConfirmFilterField.column`, bean `PasswordEncoder` | Xóa |
| L14 | `pom.xml` (thay đổi chưa commit) | `poi-ooxml` đang bị hạ từ 5.4.1 xuống 5.2.5 | Xác nhận lý do; nên giữ bản mới hơn |

## 5. Sai lệch giữa README cũ và code (đã sửa trong README mới)

- `GET /api/backlogs?page=&size=` **không tồn tại** (trả 404). API thật là `POST /api/backlogs/search`.
- README cũ thiếu toàn bộ tài liệu cho Backlog (4 API), Shipment Fulfillment, ODBF, Auth và Machine Monitor.
- FAC Confirm:
  - "Mọi lỗi trả `{status, message}`" chỉ đúng với `FacConfirmController`.
  - Thiếu response và lỗi của `/daily-summary`.
  - Thiếu một số operator và lỗi filter.
  - `logicOperator` sai giá trị bị coi là `and`, không báo lỗi.
- Machine Locator:
  - Import không bắt buộc `ifVersion`.
  - History không phải "mỗi máy thay đổi một dòng": import ghi cho mọi máy, `confirm` luôn tăng version.
  - Thiếu quy tắc của rename, revert và import.

## 6. Ưu tiên xử lý đề xuất

1. **Ngay:** H1 (sửa SELECT, khoảng 10 phút), H5 (đổi mật khẩu DB, chuyển sang biến môi trường), H6 (`dispose()`), H7 (cấu hình async timeout).
2. **Ngắn hạn:** H2 + H3 + H4 (xác thực bằng token, khóa `/register`, hash mật khẩu; cần phối hợp team S-Patrol). Giới hạn CORS. M6/M7 (thống nhất xử lý lỗi).
3. **Trung hạn:** M1, M2, M5, M8–M15. Viết thêm test cho Backlog, Shipment, ODBF, Machine Locator.
