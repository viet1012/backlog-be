# backlog-be

Backend REST API cho hệ thống quản lý sản xuất nhà máy F2: theo dõi backlog đơn sản xuất (PO), xác nhận tiến độ công đoạn (FAC Confirm), báo cáo giao hàng / ODBF, bản đồ vị trí máy (Machine Locator) và giám sát an toàn theo máy (Machine Monitor).

> Báo cáo audit chức năng và rủi ro: [docs/API_AUDIT.md](docs/API_AUDIT.md).

## Mục lục

1. [Công nghệ](#1-công-nghệ)
2. [Bắt đầu nhanh](#2-bắt-đầu-nhanh)
3. [Cấu trúc dự án](#3-cấu-trúc-dự-án)
4. [Quy ước chung của API](#4-quy-ước-chung-của-api)
5. [Danh sách API](#5-danh-sách-api)
6. [Auth: Production Control](#6-auth-production-control)
7. [Backlog](#7-backlog)
8. [Shipment Fulfillment](#8-shipment-fulfillment)
9. [ODBF](#9-odbf)
10. [FAC Confirm](#10-fac-confirm)
11. [Machine Locator](#11-machine-locator)
12. [Machine Monitor](#12-machine-monitor)
13. [Bảng dữ liệu](#13-bảng-dữ-liệu)
14. [Hạn chế đã biết](#14-hạn-chế-đã-biết)

---

## 1. Công nghệ

| Thành phần | Phiên bản / ghi chú |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.6 (`spring-boot-starter-web`, `spring-boot-starter-jdbc`) |
| Database | SQL Server, driver `mssql-jdbc`, database `F2Database` |
| Truy cập dữ liệu | `JdbcTemplate` / `NamedParameterJdbcTemplate`, SQL viết tay, **không** dùng JPA/Entity |
| Excel | Apache POI `poi-ooxml` (SXSSF, ghi stream) |
| Khác | Lombok, `spring-security-crypto` (chỉ khai báo bean, hiện không dùng) |
| Build | Maven Wrapper (`mvnw` / `mvnw.cmd`, Maven 3.9.9) |

## 2. Bắt đầu nhanh

### 2.1. Yêu cầu

- JDK 17+
- Kết nối được tới SQL Server chứa `F2Database`

### 2.2. Cấu hình

File cấu hình: [src/main/resources/application.properties](src/main/resources/application.properties).

| Key | Ý nghĩa |
|---|---|
| `server.port` | Cổng HTTP, mặc định `8080` |
| `spring.datasource.url` / `username` / `password` | Kết nối SQL Server |
| `spring.datasource.hikari.maximum-pool-size` | Số kết nối tối đa (hiện `10`) |
| `machine-monitor.hse.plants` | Danh sách `plant` của `F2_Patrol_Report`, phân cách bằng dấu phẩy (hiện `Fac_2`) |
| `machine-monitor.hse.division-floor` | Map `division=floorId`, phân cách bằng dấu phẩy. Dùng để gán lỗi an toàn không ghi máy vào đúng tầng |

**Khuyến nghị:** không lưu thông tin đăng nhập DB trong file commit vào git. Dùng biến môi trường:

```properties
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
```

```powershell
$env:DB_URL = "jdbc:sqlserver://<host>:1433;databaseName=F2Database;encrypt=false;trustServerCertificate=true"
$env:DB_USERNAME = "<user>"
$env:DB_PASSWORD = "<password>"
```

### 2.3. Chạy

```bash
./mvnw spring-boot:run                                         # Linux / macOS
.\mvnw.cmd spring-boot:run                                     # Windows
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev      # bật endpoint debug
```

> Ứng dụng đọc metadata cột của `F2_Backlog_Main` khi khởi động (`BacklogColumnMetadataProvider`), nên **không khởi động được nếu DB không kết nối được**.

### 2.4. Test và build

```bash
.\mvnw.cmd test          # unit test (không cần DB)
.\mvnw.cmd package       # tạo target/backlog-be-0.0.1-SNAPSHOT.jar
java -jar target/backlog-be-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Hiện chỉ có test cho module FAC Confirm (`src/test/java`).

### 2.5. Script SQL

| File | Mục đích | Trạng thái |
|---|---|---|
| [sql/fac_confirm_unique_index.sql](sql/fac_confirm_unique_index.sql) | Tạo unique index `(AUNFR, ProcessGrp)` cho `F2_Backlog_Fac_Confirm`. Tự dừng nếu còn bản ghi trùng | **Chưa chạy**, cần dọn dữ liệu trùng trước |
| [sql/migrate_production_control_account_to_patrol.sql](sql/migrate_production_control_account_to_patrol.sql) | Chuyển tài khoản từ `F2_ProductionControl_Account` sang `HSE_Patrol_Account` (chạy một lần, có bước preview) | Migration một lần |

DDL của các bảng `F2_MachineLocator_*` chưa có trong repo (bảng được tạo tay).

## 3. Cấu trúc dự án

```
src/main/java/com/example/backlogbe
├── config/          PasswordConfig (bean BCrypt, hiện không dùng)
├── controller/      REST controller, mỗi module một file
├── dto/             Request / response (record), chia theo module
├── exception/       Exception handler (global + riêng FacConfirm, MachineLocator)
├── model/           Row model đọc từ DB
├── repository/      SQL (JdbcTemplate), filter SQL builder, row mapper
├── service/         Nghiệp vụ, validate, export Excel
└── utils/           SmartDateTimeSerializer
```

Luồng xử lý: `Controller` → `Service` (validate + nghiệp vụ) → `Repository` (SQL có tham số bind).

## 4. Quy ước chung của API

### 4.1. Base URL, CORS, xác thực

- Base URL: `http://<host>:8080`
- Mọi controller đều bật `@CrossOrigin(origins = "*")`.
- **Không có token / session.** Đăng nhập chỉ trả thông tin người dùng và role. Các API ghi của Machine Locator nhận danh tính qua header `X-Employee-Id`. FAC Confirm nhận `employeeId` trong body. Mọi API còn lại không yêu cầu đăng nhập. Xem [mục 14](#14-hạn-chế-đã-biết).

### 4.2. Định dạng lỗi

Định dạng body lỗi **khác nhau theo module**:

| Module | Body lỗi | Ghi chú |
|---|---|---|
| FAC Confirm, Machine Locator | `{ "status": 400, "message": "..." }` | Handler riêng (`FacConfirmExceptionHandler`, `MachineLocatorExceptionHandler`). Lỗi 409 của Machine Locator có thêm `version`. Lỗi không lường trước trả 500 với message chung, chi tiết chỉ ghi log |
| Backlog, Shipment, ODBF, Auth | `{ "message": "..." }` | `GlobalExceptionHandler`: `IllegalArgumentException` → 400, `IllegalStateException` → **403** |
| Lỗi còn lại (thiếu/sai kiểu tham số, JSON hỏng, SQL, `ResponseStatusException` ngoài 2 module đầu, Machine Monitor) | Mặc định Spring Boot `{ timestamp, status, error, path }` | Không có `message` vì `server.error.include-message` chưa bật |

### 4.3. Định dạng ngày giờ

| Vị trí | Định dạng |
|---|---|
| Tham số đầu vào (query, filter) | `yyyy-MM-dd`; datetime dạng ISO `yyyy-MM-ddTHH:mm:ss` |
| `BacklogMainDto`, `FacConfirmDto` (response) | `dd/MM/yyyy` nếu giờ là 00:00:00, ngược lại `dd/MM/yyyy HH:mm:ss` (`SmartDateTimeSerializer`) |
| Các DTO khác | ISO-8601 (`2026-10-01T08:30:00`) |

Mọi so sánh thời gian dùng `LocalDateTime` (không có múi giờ), theo giờ địa phương của server.

### 4.4. Phân trang

API phân trang trả `PageResponse<T>`:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "first": true, "last": true }
```

`page` bắt đầu từ `0`, `page < 0` được đưa về `0`, `size` bị giới hạn trong `1..200`.

### 4.5. Bộ lọc kiểu Excel (Backlog, FAC Confirm)

Một filter: `{ "field": "...", "operator": "...", "value": "...", "values": [] }`. Các filter nối với nhau bằng `logicOperator` (`and` / `or`). Operator không phân biệt hoa thường và bỏ qua khoảng trắng.

| Kiểu cột | Operator |
|---|---|
| TEXT | `contains`, `doesNotContain`, `equals`/`is`/`=`, `doesNotEqual`/`not`/`!=`, `startsWith`, `endsWith`, `isEmpty`, `isNotEmpty`, `in`/`isAnyOf` |
| NUMBER | `=`/`equals`/`is`, `!=`/`doesNotEqual`/`not`, `>`, `>=`, `<`, `<=`, `isEmpty`, `isNotEmpty`, `in`/`isAnyOf` |
| DATE | `is`/`equals`/`=`, `not`/`!=`/`doesNotEqual`, `after`, `onOrAfter`, `before`, `onOrBefore`, `isEmpty`, `isNotEmpty`, `in`/`isAnyOf` |

- Với `in`/`isAnyOf`, phần tử rỗng `""` trong `values` nghĩa là NULL (hoặc chuỗi rỗng).
- Giá trị ngày: `yyyy-MM-dd`, hoặc ISO datetime (chỉ lấy 10 ký tự đầu). So sánh theo cả ngày.
- `doesNotContain`, `doesNotEqual`, `!=` có tính cả dòng NULL.

## 5. Danh sách API

| Method | Path | Mô tả | Xác thực |
|---|---|---|---|
| POST | `/api/production-control/auth/register` | Tạo tài khoản | – |
| POST | `/api/production-control/auth/login` | Đăng nhập, trả thông tin + role | – |
| POST | `/api/backlogs/search` | Danh sách backlog: tìm kiếm, lọc, sắp xếp, phân trang | – |
| POST | `/api/backlogs/filter-options` | Giá trị distinct cho menu lọc | – |
| POST | `/api/backlogs/summary/status` | Ma trận Status × ngày xuất | – |
| POST | `/api/backlogs/export/excel` | Xuất Excel backlog | – |
| GET | `/api/shipment-fulfillment` | Tỷ lệ hoàn thành giao hàng theo ngày / khách / ShipBy | – |
| GET | `/api/shipment-fulfillment/detail` | Chi tiết PO của một nhóm giao hàng | – |
| GET | `/api/odbf/summary` | Tổng hợp ODBF (On-time Delivery) theo nhóm SP / ngày | – |
| GET | `/api/fac-confirm` | Danh sách FAC Confirm (API cũ, không filter) | – |
| POST | `/api/fac-confirm/search` | Danh sách FAC Confirm có filter | – |
| POST | `/api/fac-confirm/filter-options` | Giá trị distinct cho menu lọc | – |
| POST | `/api/fac-confirm/process-groups` | Thẻ tổng hợp Rough / Heat / Fine | – |
| GET | `/api/fac-confirm/daily-summary` | Thẻ tổng hợp theo từng ngày | – |
| POST | `/api/fac-confirm/confirmed-processes` | Các ô đã xác nhận qua FAC Confirm | – |
| PATCH | `/api/fac-confirm/process-times` | Lưu thời gian xác nhận | `employeeId` trong body |
| POST | `/api/fac-confirm/export/excel` | Xuất Excel FAC Confirm | – |
| GET | `/api/fac-confirm/debug-client` | Debug nhận diện máy client (chỉ profile `dev`) | – |
| GET | `/api/machine-locator/floors` | Danh sách tầng | – |
| GET | `/api/machine-locator/floors/{id}` | Bản đồ một tầng | – |
| PUT | `/api/machine-locator/floors/{id}/machines/{code}` | Thêm / sửa vị trí máy | `X-Employee-Id` (ADMIN) |
| DELETE | `/api/machine-locator/floors/{id}/machines/{code}` | Xóa máy | `X-Employee-Id` (ADMIN) |
| POST | `/api/machine-locator/floors/{id}/machines/{code}/rename` | Đổi mã máy | `X-Employee-Id` (ADMIN) |
| POST | `/api/machine-locator/floors/{id}/machines/{code}/confirm` | Xác nhận máy đã review | `X-Employee-Id` (ADMIN) |
| POST | `/api/machine-locator/floors/{id}/machines/{code}/revert` | Khôi phục vị trí theo CAD | `X-Employee-Id` (ADMIN) |
| POST | `/api/machine-locator/floors/{id}/bulk` | Ghi nhiều máy một lần | `X-Employee-Id` (ADMIN) |
| POST | `/api/machine-locator/floors/{id}/import` | Import file layout | `X-Employee-Id` (ADMIN) |
| GET | `/api/machine-monitor/hse` | Tình trạng lỗi an toàn (HSE Patrol) theo máy | – |

---

## 6. Auth: Production Control

Base path: `/api/production-control/auth`. Code: `ProductionControlAuthController` → `ProductionControlAuthService` → `ProductionControlAuthRepository`.

Tài khoản dùng chung với hệ thống S-Patrol (bảng `HSE_Patrol_Account`). Thông tin nhân sự lấy từ `F2_HR_Data`. Mã nhân viên được `trim` và viết hoa trước khi xử lý.

### 6.1. `POST /register`

Body: `{ "employeeId": "12345", "password": "..." }`

1. `employeeId` phải tồn tại trong `F2_HR_Data`.
2. Chưa có tài khoản trong `HSE_Patrol_Account`.
3. Ghi `Account`, `Pass`, `NewDT`, `UpdDT`.

```json
{ "accountId": null, "employeeId": "12345", "status": "ACTIVE", "message": "Account created successfully." }
```

| Lỗi 400 `{message}` |
|---|
| `Employee ID (MSNV) is required.`, `Password is required.` |
| `Employee ID does not exist in company HR data.` |
| `Employee ID already has an account. Please log in with your S-Patrol password.` |

### 6.2. `POST /login`

Body: `{ "employeeId": "12345", "password": "..." }`. Thành công thì cập nhật `Last_Login`.

```json
{
  "accountId": null, "employeeId": "12345", "name": "...", "fac": "...", "dept": "...",
  "section": "...", "line": "...", "group": "...", "status": "ACTIVE", "roles": ["PC"]
}
```

Lỗi 400: `Employee ID and password are required.`, `Invalid Employee ID or password.`

### 6.3. Quy tắc role

Role được tính lại mỗi lần (không lưu DB), từ `Dept`, `Section`, `Line`, `Group` của `F2_HR_Data` (bản ghi `id` lớn nhất):

| Role | Điều kiện |
|---|---|
| `ADMIN` | `Dept = 'K COMMON'` và Section/Line/Group chứa `PE IT` (chỉ trả mỗi `ADMIN`) |
| `PC` | Section/Line/Group chứa `PC` |
| `PRO` | Section/Line/Group chứa `PRO` |
| `USER` | Không khớp role nào, hoặc không có dữ liệu HR |

Một người có thể vừa `PC` vừa `PRO`. Machine Locator dùng cùng logic này (`findAccountRoles`) để kiểm tra quyền ADMIN.

---

## 7. Backlog

Base path: `/api/backlogs`. Nguồn dữ liệu: `F2_Backlog_Main` (chỉ đọc).
Code: `BacklogMainController` → `BacklogMainService` / `BacklogExcelService` → `repository/backlog/*`.

### 7.1. `POST /search`: danh sách

| Tham số (query) | Kiểu | Mặc định | Ghi chú |
|---|---|---|---|
| `page` | int | `0` | |
| `size` | int | `20` | `1..200` |
| `search` | string | `""` | `LIKE %x%` trên `VBELN`, `ZGLOBAL_CODE`, `PIER_AUFNR`, `AUFNR`, `PNAME`, `RRONYU1`, `MTO_ID`, `CurrentProcess`, `ShipBy` |
| `sort` | string | `UpdatedAt DESC, VBELN DESC` | Dạng `field,asc` hoặc `field,desc`. `field` là tên cột DB (phân biệt hoa thường) trong whitelist 52 cột. Thiếu dấu phẩy thì dùng sort mặc định |

Body (tùy chọn): `{ "filters": [ ...filter ], "logicOperator": "and" }` (xem [4.5](#45-bộ-lọc-kiểu-excel-backlog-fac-confirm)).

- `field` của filter so khớp **không phân biệt hoa thường** với mọi cột của `F2_Backlog_Main`. Kiểu cột (TEXT/NUMBER/DATE) lấy từ metadata DB.
- Filter `Status = Finished` được đổi thành `WIP_FG`.
- `search` và `filters` nối với nhau bằng AND.

Response: `PageResponse<BacklogMainDto>`, gồm toàn bộ cột của `F2_Backlog_Main` theo camelCase:

```json
{
  "content": [{
    "vbeln": "...", "zglobalCode": "...", "pierAufnr": "...", "aufnr": "123456",
    "issueD": "03/09/2026", "productionD": "03/09/2026 08:25:12", "exportD": "10/09/2026",
    "pname": "...", "rronyu1": "...", "shipBy": "...", "gamng": 10.000, "status": "WIP",
    "currentProcess": "...", "finalQty": 10, "classify": "Sale", "processGrp2": "Rough",
    "productGrp": "...", "status2": "ON PROGRESS", "waitingDays": 2.0, "heatNote": null,
    "updater": "...", "updatedAt": "01/11/2026 10:00:00"
  }],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true
}
```

(Ví dụ đã rút gọn. Danh sách đầy đủ: [BacklogMainDto.java](src/main/java/com/example/backlogbe/dto/backlog/BacklogMainDto.java).)

### 7.2. `POST /filter-options`: giá trị cho menu lọc

Body: `{ "field": "Status", "filters": [], "logicOperator": "and", "search": "", "limit": 100 }`

- `field` bắt buộc, phải nằm trong whitelist (phân biệt hoa thường).
- Filter của chính `field` bị bỏ qua. `search` lọc `LIKE` trên chính giá trị của `field` (không phải search toàn bảng).
- `limit` mặc định 100, tối đa 500. Chỉ áp dụng cho các cột nhiều giá trị: `VBELN`, `ZGLOBAL_CODE`, `PIER_AUFNR`, `AUFNR`, `PNAME`, `RRONYU1`, `MTO_ID`, `HeatCharge`, `Updater`.

Response: mảng `string` distinct, đã sắp xếp. NULL thành `""`, ngày dạng `yyyy-MM-dd`.

### 7.3. `POST /summary/status`: ma trận Status × ngày xuất

Body (tùy chọn): như filter của `/search`. Filter trên cột `Status` bị bỏ qua. Không hỗ trợ `search`.

```json
{
  "totalPoCount": 120, "totalQty": 5400.0000,
  "dates": ["2026-11-01", "2026-11-02"],
  "rows": [
    { "status": "NYI", "values": [
      { "date": "2026-11-01", "poCount": 3, "qty": 120.0000 },
      { "date": "2026-11-02", "poCount": 0, "qty": 0 }
    ] }
  ]
}
```

- `totalPoCount` / `totalQty`: số dòng và tổng `FinalQty` của mọi dòng thỏa filter, **kể cả** dòng không có `ExportD`.
- `dates`: các ngày `ExportD` distinct, tăng dần. Dòng không có `ExportD` không nằm trong ma trận.
- `rows`: theo `Status` (`WIP_FG` hiển thị `Finished`, rỗng hiển thị `(Blank)`). Thứ tự: NYI, NY Process, WIP, Finished, sau đó theo alphabet. Ô thiếu = 0.

### 7.4. `POST /export/excel`: xuất Excel

`Content-Type: application/json`. Body:

```json
{
  "filter": { "filters": [], "logicOperator": "and" },
  "search": "",
  "sort": "ExportD,asc",
  "columns": ["VBELN", "AUFNR", "IssueD", "ExportD", "Status"]
}
```

- `columns` bắt buộc, không trùng, là tên cột DB trong danh mục 52 cột (phân biệt hoa thường). Thứ tự cột trong file giữ theo request.
- Response: file `backlog_yyyyMMdd_HHmmss.xlsx`, sheet `Backlog`. Dòng 1–3: tiêu đề, thời điểm xuất, mô tả filter. Header ở dòng 6. Có freeze pane, autofilter, tô màu theo Status.
- Giới hạn 1.048.570 dòng dữ liệu. Vượt giới hạn trả **403** (xem [mục 14](#14-hạn-chế-đã-biết)).
- Lỗi `columns` trả 400 theo định dạng mặc định của Spring Boot (không có message).

---

## 8. Shipment Fulfillment

Base path: `/api/shipment-fulfillment`. Nguồn: `F2_Backlog_Main`.
Code: `ShipmentFulfillmentController` → `ShipmentFulfillmentService` → `ShipmentFulfillmentRepository`, `ShipmentDetailRepository`.

### 8.1. `GET /?fromD=yyyy-MM-dd&toD=yyyy-MM-dd`

Tổng hợp theo (`ExportD`, khách hàng, `ShipBy`) với `fromD <= ExportD < toD + 1 ngày`. Cả hai tham số đều bắt buộc.

```json
[
  { "exportD": "2026-11-01T00:00:00", "cusId": "Stock", "shipBy": "EXP",
    "poQty": 120, "fnQty": 90, "fnRatio": 0.750000 }
]
```

| Field | Nguồn |
|---|---|
| `cusId` | `RRONYU1`, NULL hiển thị `Stock` |
| `poQty` | `SUM(GAMNG)` |
| `fnQty` | `SUM(PkQty)` |
| `fnRatio` | `fnQty / poQty` (tỷ lệ 0–1, không phải %); `poQty = 0` thì trả 0 |

### 8.2. `GET /detail?cusId=&shipBy=&exportDate=yyyy-MM-dd`

Danh sách PO (`BacklogMainDto`) của một ô tổng hợp. Cần ít nhất một tham số, nếu không có trả 400 `At least one filter is required: cusId, shipBy or exportDate`.

| Tham số | Quy tắc |
|---|---|
| `cusId` | `Stock` (không phân biệt hoa thường) → `RRONYU1 IS NULL`; khác → `RRONYU1 = cusId` |
| `shipBy` | `N/A` → `ShipBy IS NULL`; `EXP`/`EXPRESS` → khớp cả hai; khác → so sánh không phân biệt hoa thường |
| `exportDate` | Lọc theo cả ngày |

Sắp xếp: `ExportD, RRONYU1, ShipBy, VBELN, AUFNR`. Không phân trang.

> **Lỗi đã biết:** API này hiện trả 500 khi có kết quả, vì câu SELECT thiếu các cột `BacklogRowMapper` cần đọc. Xem [docs/API_AUDIT.md](docs/API_AUDIT.md).

---

## 9. ODBF

### `GET /api/odbf/summary`

Tổng hợp tỷ lệ giao hàng đúng hạn (ODBF) của Div `PR`, theo `ProductGrp` × `ExportD` × trạng thái. Không có tham số. Mỗi lần gọi đều tính trên toàn bộ dữ liệu.

```json
[
  { "productGrp": "Retainer", "status2": "OK", "exportD": "2026-11-01T00:00:00",
    "countPo": 40, "sumQty": 520.0000, "poRatio": 80.00, "qtyRatio": 76.47 }
]
```

Logic (`OdbfRepository`, `OdbfService`):

1. Chỉ lấy dòng có `CountODBF <> 'No Count'` và `Div = 'PR'`.
2. Loại PO "Short LT": `ExportD − IssueD − ngày chờ ≤ 1`. Ngày chờ là `WaitingDays`, nếu không có thì Retainer = 3, Sprue Bush / Taper Block = 1, còn lại = 0. Không loại PO có `PRT_ADDCMT2 LIKE '%Bu%'` hoặc `AUFNR LIKE '72%'`.
3. `status2`:
   - Đã đóng gói (`COALESCE(ToPK, PK_Received)` có giá trị): `OK` nếu `≤ ExportD`, ngược lại `Late`.
   - Chưa đóng gói: mô phỏng theo FAC Confirm process `To Packing`: `OK` nếu `ConfirmFnTime < ExportD`, ngược lại `Late`.
4. `poRatio` / `qtyRatio`: % số PO / số lượng có trạng thái `OK` (hoặc `COMPLETED`) trong cùng (`productGrp`, `exportD`), làm tròn 2 chữ số. Giá trị lặp lại trên mọi dòng của nhóm.

---

## 10. FAC Confirm

Module cho phép xưởng xem các PO (production order, `AUFNR`) đang tồn trong `F2_Backlog_Main` và **xác nhận thời điểm hoàn thành từng process** (To Drill, To Heat, To CLG, To Packing). Kết quả lưu vào `F2_Backlog_Fac_Confirm`.

Dữ liệu xem theo 3 công đoạn (`procGrp`): **Rough**, **Heat**, **Fine**.

| Thành phần | File |
|---|---|
| Controller | `controller/FacConfirmController.java`, `controller/FacConfirmDebugController.java` (profile `dev`) |
| Danh sách, filter options, thẻ tổng hợp, daily summary | `service/FacConfirmService.java` → `repository/facconfirm/FacConfirmRepository.java` |
| Lưu, confirmed-processes | `service/FacConfirmProcessTimeService.java` → `repository/facconfirm/FacConfirmProcessTimeRepository.java` |
| Export Excel | `service/FacConfirmExcelService.java` |
| Nhận diện máy client | `service/ClientMachineService.java` |
| Quy tắc nghiệp vụ | `repository/facconfirm/FacConfirmEditRules.java` |
| Xử lý lỗi | `exception/FacConfirmExceptionHandler.java` |

### 10.1. Field và công đoạn

| field | Tên hiển thị | `ProcessGrp` (DB) | Cột `F2_Backlog_Main` | Công đoạn sở hữu |
|---|---|---|---|---|
| `toDrill` | To Drill | `To Drill` | `ToDrill` | Rough |
| `toHeat` | To Heat | `To Heat` | `ToHeat` | Rough |
| `heatStart` | Heat Start | `Heat Start` | `TimeSQuenching` | Heat (**chỉ xem**) |
| `heatFinish` | **To CLG** | `Heat Finish` | `TimeFHeat` | Heat; **Rough** với dòng không có Heat |
| `toPk` | To Packing | `To Packing` | `ToPK` | Fine |

Giá trị hiển thị mỗi ô = `COALESCE(<cột F2_Backlog_Main>, <bản ghi Fac Confirm mới nhất theo UpdatedAt>)`: **dữ liệu Backlog được ưu tiên**.

### 10.2. Quy tắc nghiệp vụ

Tất cả quy tắc tập trung trong `FacConfirmEditRules`. Không viết lại ở chỗ khác.

**Dòng "không có Heat"** (`NO_HEAT_CONDITION`):

```sql
UPPER(LTRIM(RTRIM(ISNULL(bl.Heat_Note, '')))) = 'NO HEAT'
AND ISNULL(bl.WaitingDays, -1) NOT IN (2, 5, 7)
```

WaitingDays 2 / 5 / 7 hiển thị Heat Note `TD chờ 2 ngày` / `DC53 chờ 5 ngày` / `Molypden chờ 7 ngày` và được coi là **có Heat**.

**Quyền sửa ô** (`getEditableFields`):

| Công đoạn | Dòng thường | Dòng không có Heat |
|---|---|---|
| Rough | `toDrill`, `toHeat` | `toDrill`, `heatFinish` |
| Heat | `heatFinish` | (dòng không thuộc Heat) |
| Fine | `toPk` | `toPk` |

`heatStart` chỉ để xem ở mọi công đoạn. Request lưu không gửi `procGrp` thì field chỉ cần được phép ở ít nhất một công đoạn.

**Thẻ "Cần xác nhận" / "Đã xác nhận"** (`findProcessGroups`):

| Thẻ | Dòng được tính (`ProcessGrp2`) | Process cuối, dòng thường | Process cuối, không Heat |
|---|---|---|---|
| Rough | `Rough` hoặc NULL | `To Heat` | `Heat Finish` |
| Heat | `Heat`, `Rough`, NULL; loại dòng không Heat | `Heat Finish` | – |
| Fine | `Fine`, `Heat`, `Rough`, NULL | `To Packing` | `To Packing` |

- Cần xác nhận = số dòng và tổng `FinalQty`.
- Đã xác nhận = các dòng có bản ghi Fac Confirm của process cuối với `ConfirmFnTime IS NOT NULL`. Chỉ xét `F2_Backlog_Fac_Confirm`, **không** xét giá trị có sẵn trong `F2_Backlog_Main`.

**Điều kiện lọc chung** (danh sách, đếm, filter options, export):

| Điều kiện | SQL |
|---|---|
| Ngày xuất | `ExportD <= expD` (so với `expD 00:00:00`) |
| Công đoạn | Fine: `ProcessGrp2 IN (Fine, Heat, Rough)` hoặc NULL; Heat: `IN (Heat, Rough)` hoặc NULL; Rough: `= Rough` hoặc NULL |
| Heat | `procGrp = Heat` thì loại dòng không có Heat |
| Div | `Div = div`; riêng `div = 'GU'` → `Div LIKE '%G'` |
| Không cần confirm | Loại `IsNoCount = 1`: `ProductGrp = 'Cam'`, `FERTH LIKE 'Backing Plug%'`, `PHCD LIKE 'J%'`, `ProcessGrp2 = 'MTO'`, (`ZGLOBAL_CODE IS NULL` và `PRT_ADDCMT2 NOT LIKE '%FNK%'`), hoặc `Status2 <> 'ON PROGRESS'` |
| `classify` | `Sale` → `Classify = 'Sale'`; `Stock` → `Classify <> 'Sale'` |
| `heatType` | `Normal`: không DC53/TD/Molypden; `DC53`: `WaitingDays = 5`; `TD`: `= 2`; `Molypden`: `= 7` |

**Validate thời gian khi lưu** (`validateTimes`):
- Dòng thường: To Drill ≤ To Heat ≤ Heat Start ≤ To CLG ≤ To Packing.
- Dòng không Heat: To Drill ≤ To CLG ≤ To Packing.
- So với giá trị đang hiển thị cộng các thay đổi trong cùng request. Chỉ báo lỗi cho cặp có ít nhất một ô đang sửa.
- **Không** giới hạn thời gian tương lai (theo yêu cầu nghiệp vụ).

**Ô đã có dữ liệu Backlog:** API lưu từ chối ô có giá trị trong `F2_Backlog_Main`. `/confirmed-processes` chỉ trả bản ghi của process mà cột Backlog tương ứng đang NULL.

**Thêm quy tắc / field mới:** sửa `FacConfirmEditRules` (hằng số field, `FIELD_TO_DB_PROCESS`, `FIELD_TO_BACKLOG_COLUMN`, `FIELD_LABELS`, `DEFAULT_OWNER_PROCESS`, thứ tự thời gian, `getEditableFields`, `READ_ONLY_FIELDS`). Sau đó cập nhật `FacConfirmDto`, `FacConfirmRowMapper`, `FacConfirmColumnMetadataProvider`, `FacConfirmFilterField`, cột trong `FacConfirmExcelService`, CTE `FacData` / `ConfirmPivot`, `findConfirmedProcesses`, thêm test vào `FacConfirmEditRulesTest`, và đồng bộ FE `backlog-web/src/config/facConfirmEditRules.ts`.

### 10.3. Tham số dùng chung

| Tên | Kiểu | Bắt buộc | Giá trị |
|---|---|---|---|
| `div` | string | có | Div |
| `expD` | `yyyy-MM-dd` | có | Lấy `ExportD <= expD` |
| `procGrp` | string | có | `Rough` / `Heat` / `Fine` |
| `classify` | string | không | `Sale` / `Stock` |
| `heatType` | string | không (`All`) | `All` / `Normal` / `DC53` / `TD` / `Molypden` |
| `filters` | array | không | Tối đa 50. `field` là key camelCase của DataGrid (phân biệt hoa thường) |
| `logicOperator` | string | không (`and`) | `and` / `or`; giá trị khác được coi là `and` |

Lỗi validate thường gặp (400): `div is required`, `expD is required`, `procGrp is required`, `Invalid procGrp: X. Allowed values: Fine, Heat, Rough`, `classify must be Sale or Stock`, `heatType must be All, Normal, DC53, TD or Molypden`, `Too many filters. Maximum allowed: 50`, `Unsupported Fac Confirm filter field: X`, `Unsupported text|number|date operator: X`, `Invalid number for F: v`, `Invalid date for F: v. Expected yyyy-MM-dd.`

### 10.4. Endpoint

#### `GET /api/fac-confirm`: danh sách (API cũ)

Query: tham số dùng chung (trừ `filters`), `page` (mặc định 0), `size` (mặc định 100, tối đa 200). Response: `PageResponse<FacConfirmDto>`.

```json
{
  "content": [{
    "ferth": "...", "productGrp": "...", "aufnr": "123456", "zglobalCode": "...", "pname": "...",
    "issueD": "01/10/2026", "exportD": "10/10/2026", "cusId": "...", "shipBy": "...", "mtoId": "...",
    "prtAddcmt2": "...", "currentProcess": "...", "finalQty": 10,
    "waitingDays": 5, "hasHeatProcess": true, "isDC53": true, "isTD": false, "isMolypden": false,
    "note": "DC53 chờ 5 ngày",
    "toDrill": "02/10/2026 08:00:00", "toHeat": null, "heatStart": null, "heatFinish": null, "toPk": null
  }],
  "page": 0, "size": 100, "totalElements": 1, "totalPages": 1, "first": true, "last": true
}
```

#### `POST /api/fac-confirm/search`: danh sách có filter

Body: tham số dùng chung, cộng `search` (`LIKE` trên FERTH, ProductGrp, AUFNR, ZGLOBAL_CODE, PNAME, CusId, ShipBy, MTO_ID, PRT_ADDCMT2, CurrentProcess, Note), `page` (0), `size` (20, tối đa 200). Response như trên.

#### `POST /api/fac-confirm/filter-options`

Body: `field` (bắt buộc) + tham số dùng chung + `search` (lọc `LIKE` trên chính giá trị của `field`). Filter của chính `field` bị bỏ qua, các filter khác nối bằng `and`. Response: mảng `string` distinct (ngày `yyyy-MM-dd`, NULL → `""`).

#### `POST /api/fac-confirm/process-groups`

Body: `div`, `expD` (bắt buộc), `classify`, `heatType`, `filters`, `logicOperator`.

```json
[
  { "processGroup": "Rough", "requiredOrderCount": 120, "requiredTotalQty": 560.00,
    "confirmedOrderCount": 80, "confirmedTotalQty": 400.00 }
]
```

Thẻ không có dòng nào thì không xuất hiện trong mảng.

#### `GET /api/fac-confirm/daily-summary`

| Tham số | Bắt buộc | Giá trị |
|---|---|---|
| `division` | có | như `div` |
| `from`, `to` | có | `yyyy-MM-dd`, `from <= to`, tối đa 92 ngày (tính cả hai đầu) |
| `saleType` | không (`ALL`) | `ALL` / `SALE` / `STOCK` |
| `heatType` | không (`All`) | như trên |
| `dateBy` | không (`EXPORT`) | `EXPORT`: đã xác nhận theo `ExportD`; `CONFIRM`: theo `ConfirmFnTime` của process cuối (chỉ xét `ExportD <= to`) |

```json
{
  "from": "2026-10-01", "to": "2026-10-31", "dateBy": "EXPORT",
  "days": [
    { "date": "2026-10-01",
      "rough": { "needPo": 10, "needPcs": 50, "donePo": 8, "donePcs": 40 },
      "heat":  { "needPo": 6,  "needPcs": 30, "donePo": 3, "donePcs": 15 },
      "fine":  { "needPo": 12, "needPcs": 60, "donePo": 5, "donePcs": 25 } }
  ],
  "total": { "rough": { "...": 0 }, "heat": { "...": 0 }, "fine": { "...": 0 } }
}
```

- Cần xác nhận tính theo `ExportD` trong `[from, to]` (thẻ `/process-groups` thì lũy kế `ExportD <= expD`).
- PO = `COUNT(DISTINCT AUFNR)`, Pcs = `SUM(FinalQty)`. Không áp dụng Excel filter. Ngày không có dữ liệu trả 0. `total` tính distinct trên cả khoảng.
- Lỗi (tiếng Việt): `division là bắt buộc`, `from và to là bắt buộc (định dạng yyyy-MM-dd)`, `from phải nhỏ hơn hoặc bằng to`, `Khoảng ngày tối đa 92 ngày (đang chọn N ngày)`, `saleType không hợp lệ: ...`, `dateBy không hợp lệ: ...`.

#### `POST /api/fac-confirm/confirmed-processes`

Body: mảng `aufnr` (`["123456", "123457"]`). Phần tử rỗng bị bỏ, phần tử trùng chỉ tính một lần. Mảng rỗng trả `[]`.

```json
[
  { "aufnr": "123456", "processGrp": "Heat Finish", "confirmFnTime": "2026-10-01T08:30:00",
    "updater": "PC-F2-001_22847", "updatedAt": "2026-10-01T08:31:00", "ownerProcess": "Rough" }
]
```

#### `PATCH /api/fac-confirm/process-times`: lưu thời gian xác nhận

```json
{
  "employeeId": "22847",
  "procGrp": "Rough",
  "changes": [
    { "aufnr": "123456", "field": "toDrill", "value": "2026-10-08T09:00:00" }
  ]
}
```

| Tên | Bắt buộc | Ghi chú |
|---|---|---|
| `employeeId` | có | Ghép vào cột `Updater` |
| `procGrp` | không | Có thì kiểm tra quyền theo đúng công đoạn |
| `changes` | có | Tối đa 500 thay đổi |

Xử lý trong một transaction (`@Transactional`). Có một lỗi là không lưu gì:

1. Validate request; `heatStart` bị từ chối.
2. Đọc trạng thái từng PO một lần (`findEditStates`).
3. Kiểm tra quyền sửa ô, chặn ô đã có dữ liệu Backlog, kiểm tra thứ tự thời gian.
4. `MERGE ... WITH (HOLDLOCK)` theo (`AUNFR`, `ProcessGrp`): `ConfirmFnTime`, `Updater = <tên máy>_<employeeId>`, `UpdatedAt = SYSDATETIME()`.

```json
{ "updated": 2, "clientIp": "10.0.0.5", "machineName": "PC-F2-001" }
```

`machineName` lấy bằng reverse DNS từ IP client (`X-Forwarded-For` → `X-Real-IP` → remote address). Không resolve được thì dùng IP.

Lỗi 400 ví dụ: `Employee ID is required`, `No process changes to save`, `Maximum 500 changes per request`, `Heat Start chỉ để xem, không xác nhận được: ...`, `Field not editable in Rough: 123456 (To Heat, Heat Note "Không có Heat")`, `Ô đã có dữ liệu từ Backlog, không xác nhận lại được: PO 123456: To Drill = 08/10/2026 09:00`, `Thời gian không hợp lệ: PO 123456: To CLG (...) không được trước To Drill (...)`.

#### `POST /api/fac-confirm/export/excel`

Body: tham số dùng chung + `search` + `columns` (bắt buộc; key DataGrid như `toDrill` hoặc tên cột như `Heat_Finish`; giữ thứ tự).

- File `fac_confirm_yyyyMMdd_HHmmss.xlsx`, sheet `Fac Confirm`. Đầu sheet có thông tin báo cáo và filter. Tiêu đề cột do BE đặt (`Heat_Finish` → **To CLG**).
- Validate và đếm dòng **trước** khi ghi file, nên lỗi luôn là JSON 400: `At least one export column is required`, `Unsupported export column: X`, `Duplicate export column: X`, `Dữ liệu export có N dòng, vượt giới hạn 1048570 dòng. Hãy thu hẹp bộ lọc.`

#### `GET /api/fac-confirm/debug-client` (chỉ profile `dev`)

Trả `remoteAddr`, `xForwardedFor`, `xRealIp`, `resolvedClientIp`, `machineName`. Profile khác trả 404.

---

## 11. Machine Locator

Lưu bản đồ vị trí máy theo tầng (thay JSON dev store `vite-plugins/machineLocatorDevStore.ts` của FE).
Base path: `/api/machine-locator`. Code: `MachineLocatorController` → `MachineLocatorService` → `MachineLocatorRepository`.

### 11.1. Quy tắc chung

- **Xác thực ghi:** header `X-Employee-Id`. Tài khoản phải có trong `HSE_Patrol_Account` và có role `ADMIN` ([6.3](#63-quy-tắc-role)). Thiếu header → 401 `Login is required.`; không có tài khoản → 401 `Account does not exist.`; không phải ADMIN → 403. `GET` không cần xác thực.
- **Optimistic lock:** mọi lệnh ghi gửi `ifVersion` (riêng import là tùy chọn). Service khóa dòng tầng bằng `UPDLOCK, HOLDLOCK` trong transaction. Lệch version → `409 { status, message, version }`. Thành công → `version + 1` (một lần mỗi request).
- **History:** ghi vào `F2_MachineLocator_History` (`SAVE`, `DELETE`, `RENAME`, `CONFIRM`, `REVERT`, `IMPORT`) với `before_json`, `after_json`, `changed_by`.
- **Mã máy:** được `trim` và viết hoa, phải khớp `^[AL]-\d{2,5}$`.
- **Hình học:** `boxes` không rỗng; mỗi box `{x, y, w, h, p?, fit?}` là số hữu hạn. `b` làm tròn 1 chữ số, polygon 2 chữ số. `fit` ∈ `closed | tight | hull | rect | null`. BE lưu `boxes_json = [[x,y,w,h], ...]`, `polys_json`, `fit_json` song song; nếu mọi `p` (hoặc `fit`) đều null thì lưu NULL.
- **Response của mọi lệnh ghi:** floor đầy đủ như `GET /floors/{id}`.
- Tầng không tồn tại → 404 `Floor X not found.`

### 11.2. Endpoint

| Method | Path | Body / query | Hành vi |
|---|---|---|---|
| GET | `/floors` | | `[{ id, name, drawingTitle, imageUrl, exportMeta }]` |
| GET | `/floors/{id}` | | Floor đầy đủ |
| PUT | `/floors/{id}/machines/{code}` | `{ boxes, keepStatus?, ifVersion }` | Thêm hoặc sửa. Không có `keepStatus` (hoặc máy mới): `src=manual`, `u=false`, `review=0`. Có `keepStatus`: giữ `u/src/r` |
| DELETE | `/floors/{id}/machines/{code}?ifVersion=` | | Máy không tồn tại → 404 |
| POST | `/floors/{id}/machines/{code}/rename` | `{ to, ifVersion }` | `to` hợp lệ và khác mã cũ; máy đích đã tồn tại → 400 |
| POST | `/floors/{id}/machines/{code}/confirm` | `{ ifVersion }` | Đặt `review = 0` |
| POST | `/floors/{id}/machines/{code}/revert` | `{ ifVersion }` | Chép `cad_*` về vị trí hiện tại, `src=cad`, `review=0`, giữ `u`. Không có dữ liệu CAD → 400 |
| POST | `/floors/{id}/bulk` | `{ ops: [{ type: 'set' \| 'del', code, boxes }], keepStatus?, ifVersion }` | Validate toàn bộ trước. `set` = PUT. `del` máy không tồn tại thì bỏ qua |
| POST | `/floors/{id}/import?ifVersion=` | LayoutData `{ crop: [4 số], page: [w, h], ppp, m: { code: { b, p?, fit?, u?, src?, r? } } }` | Cập nhật `crop/page/ppp` và upsert mọi máy trong `m`; máy không có trong file giữ nguyên. `src` ∈ `manual \| cad \| old`. Máy `cad`/`old` được chép vào `cad_*` để revert. `u`/`r` thiếu thì là `false` |

### 11.3. Response floor

```json
{
  "id": "factoryA-f1", "name": "...", "drawingTitle": "...", "imageUrl": "...",
  "crop": [0, 0, 100, 80], "page": [841.0, 594.0], "ppp": 2.0, "exportMeta": {}, "version": 12,
  "m": {
    "A-123": { "b": [[10.5, 20.0, 30.0, 40.0]], "p": [[[[1.25, 2.5], [3.0, 4.0]]]], "fit": ["rect"],
               "u": false, "src": "manual", "r": true }
  }
}
```

Trong mỗi máy, `p`, `fit`, `r` bị bỏ khi null; `r` chỉ có khi `review = 1`. `exportMeta`, `page` có thể là `null`.

---

## 12. Machine Monitor

### `GET /api/machine-monitor/hse?floorId=&safeFrom=&safeTo=`

Tab "An toàn": tổng hợp lỗi HSE Patrol (`F2_Patrol_Report`, `type = 'Patrol'`, `plant` thuộc `machine-monitor.hse.plants`) theo từng máy của một tầng. Chỉ đọc, không cần đăng nhập.
Code: `MachineMonitorController` → `MachineMonitorService` → `MachineMonitorRepository`.

| Tham số | Bắt buộc | Ghi chú |
|---|---|---|
| `floorId` | có | Phải tồn tại trong `F2_MachineLocator_Floor`, nếu không trả 404 |
| `safeFrom`, `safeTo` | không | `yyyy-MM-dd`, tính cả hai đầu. **Chỉ** lọc các lỗi đã SAFE theo ngày `hse_date`; có tham số thì lỗi SAFE không có `hse_date` bị bỏ |

`boxes` của PUT / bulk là mảng object `{x, y, w, h, p?, fit?}` (`p` = `Polygon[] | null`, `Polygon = [[x, y], ...]`). BE tách thành `boxes_json` = `[[x,y,w,h], ...]`, `polys_json` và `fit_json` là mảng song song; nếu mọi `p` (hoặc mọi `fit`) đều null/thiếu thì lưu NULL. Response vẫn là RawMachine `{b, p?, fit?, u, src, r?}`.


---

# COST MONITORING

Dựng lại 9 chart của file Excel "KVH Cost Monitoring": Div (`PR`, `MO`, `Guide`) × Chart (`OT`, `T&SL`, `RM`), trục X là các ngày trong tháng. Đơn vị: **K$**. Chỉ đọc, không cần đăng nhập.

Code: `CostMonitoringController` → `CostMonitoringService` → `CostMonitoringRepository` (NamedParameterJdbcTemplate). Mọi tính toán nằm trong SQL; service chỉ validate tham số và gom nhóm.

## Object SQL (chạy theo thứ tự trong `sql/`)
| File | Object | Vai trò |
|---|---|---|
| `01_cost_master.sql` | `[KVH].dbo.CostCenterMaster`, `CostCodeMaster` | Mapping PL cost center name / mã RM → cost center (từ sheet MasterCost). Excel đổi master thì chạy lại. |
| `02_fn_cost_actual_daily.sql` | `[KVH].dbo.fn_CostActualDaily(@From, @To)` | Actual theo ngày (K$) cho T&SL và RM |
| `04_sp_cost_monitoring_chart.sql` | `[KVH].dbo.sp_CostMonitoringChart(@MonthStart, @SimuWeek)` | 1 dòng / chart / ngày, API gọi trực tiếp |

BE luôn gọi bằng tên đầy đủ `[KVH].[dbo]...` / `[F2Database].[dbo]...`, không phụ thuộc database mặc định của datasource (`F2Database`).

## Quy tắc tính (theo SP)
- **Target** (`[F2Database].dbo.F2_PL_TGT`, `Month` dạng `'2026.10'`): Div `PR/MO/Guide` ↔ `PRESS/MOLD/GUIDE`, Chart `OT/T&SL/RM` ↔ Cate `OverTime/Tools/RM`.
  - `fcTgt` = DataType `FC`, `bp` = DataType `BP`.
  - `plTgt` = tuần `Simu_W*` được chọn. Không truyền `simuWeek` thì SP lấy **tuần Simu mới nhất có số khác 0, riêng từng Div/Cate** (các chart có thể dùng tuần khác nhau).
- **PL_MTD**: `plTgt` × (số ngày làm việc lũy kế / tổng ngày làm việc trong tháng). Ngày làm việc lấy từ `WD_All` của `[F2Database].dbo.F2_Working_Date`. Ngày không có dòng trong bảng này được tính là ngày làm việc.
- **Act_MTD**: lũy kế actual.
  - `OT`: chưa có nguồn actual, nên luôn `null`.
  - `T&SL`: tính đến hôm qua. `RM`: tính đến hôm kia. Sau mốc này `actMtd = null`.
- **Est_MTD**:
  - Từ ngày có actual cuối cùng trở về trước: `null`.
  - Sau ngày đó: `actual cuối + (PL_MTD ngày đó − PL_MTD tại ngày actual cuối)`.
  - Chart không có actual nào (vd `OT`): `Est_MTD = PL_MTD`.
- **Fluct_TGT**: `fcTgt × (1 + Adj_Ratio_{PR|MO|GU})` của ngày đó (`F2_Working_Date`). Từ hôm nay trở đi dùng tỷ lệ của hôm qua.
- **Actual** (`fn_CostActualDaily`):
  - `T&SL`: lấy từ `[KVH].dbo.CostTool` với Factory `612*`, GL `570300` / `570400`.
    - Chi phí chung `HT2`, `F2`, `HT-G` được phân bổ theo tỷ lệ **hardcode** (bảng `TTL!F2:J4` của Excel). Excel đổi thì sửa CTE `R` trong function.
  - `RM`: lấy từ `[KVH].dbo.CostRm` với Factory `612K`.
    - Bỏ GL `580000` và Movingtype `551` / `552`.
    - Chỉ tính cost center `614100` (PR), `614200` (MO), `614600` / `614700` (Guide).
  - Số được chia 1000 để ra K$. SP làm tròn 3 chữ số.

## API (`/api/cost-monitoring`)
| Method | Path | Mô tả |
|---|---|---|
| GET | `/chart?month=yyyy-MM&simuWeek=Simu_W3` | 9 chart của tháng |
| GET | `/months` | Các tháng có trong `F2_PL_TGT`, mới nhất trước, vd `["2026.10","2026.09"]` |

Tham số `/chart`:
- `month`: dạng `yyyy-MM`. Bỏ trống thì lấy tháng hiện tại.
- `simuWeek`: bỏ trống, hoặc một trong `Simu_W1`..`Simu_W5`.
- Sai định dạng → `400 { status, message }`. Lỗi khác (vd lỗi SQL) → `500` với message chung, chi tiết chỉ ghi log.
- `/months` trả dạng `2026.10`. FE cần đổi sang `2026-10` khi gọi `/chart`.

Response `/chart`:
```json
{
  "month": "2026-10", "simuWeek": "Simu_W3", "unit": "K$",
  "charts": [
    { "div": "PR", "chart": "OT", "fcTgt": 120.5, "plTgt": 118.0, "bp": 125.0,
      "actMtdLatest": null, "estMonthEnd": 118.0,
      "points": [ { "date": "2026-10-01", "plMtd": 3.9, "actMtd": null, "estMtd": 3.9, "fluctTgt": 121.2 } ] }
  ]
}
```
- `charts` luôn có đủ 9 phần tử, thứ tự PR, MO, Guide × OT, T&SL, RM. Div/Chart lạ (nếu SP trả thêm) được xếp cuối.
- `fcTgt`, `plTgt`, `bp` lấy từ dòng đầu của chart. Mọi số đều có thể `null`, BE không ép về 0.
- `actMtdLatest`: `actMtd` khác null cuối cùng.
- `estMonthEnd`: `estMtd` của ngày cuối tháng. Với tháng đã qua mà actual phủ hết tháng, giá trị này là `null` (xem quy tắc Est_MTD), nên FE dùng `actMtdLatest`.
- `simuWeek` trong response là giá trị request gửi lên. Khi bỏ trống thì trả `null`, không cho biết SP đã chọn tuần nào.
