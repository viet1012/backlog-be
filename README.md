# backlog-be

Spring Boot backend cho bảng SQL Server `F2_Backlog_Main`.

## Chức năng
- GET ALL
- Phân trang
- Read-only
- JdbcTemplate, không cần Entity/Primary Key

## Cấu hình DB
Sửa `src/main/resources/application.properties`.

## Chạy
```bash
mvn spring-boot:run
```

## API
```http
GET http://localhost:8080/api/backlogs?page=0&size=20
```

- `page` bắt đầu từ `0`
- `size` mặc định `20`
- `size` tối đa `200`

Response:
```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```


---

# FAC CONFIRM

## 1. Tổng quan

Module FAC CONFIRM cho phép xưởng xem các PO (production order, `AUFNR`) đang tồn trong `F2_Backlog_Main` và **xác nhận thời điểm hoàn thành từng process** (To Drill, To Heat, Heat Start, To CLG, To Packing). Giá trị xác nhận được lưu vào `F2_Backlog_Fac_Confirm`.

Dữ liệu được xem theo 3 công đoạn (`procGrp`): **Rough**, **Heat**, **Fine**. Mỗi công đoạn có một thẻ tổng hợp "Cần xác nhận / Đã xác nhận".

| Thành phần | File |
|---|---|
| Controller | `controller/FacConfirmController.java` |
| Danh sách, filter options, thẻ tổng hợp | `service/FacConfirmService.java` → `repository/facconfirm/FacConfirmRepository.java` |
| Lưu, confirmed-processes | `service/FacConfirmProcessTimeService.java` → `repository/facconfirm/FacConfirmProcessTimeRepository.java` |
| Export Excel | `service/FacConfirmExcelService.java` |
| Quy tắc nghiệp vụ | `repository/facconfirm/FacConfirmEditRules.java` |
| Xử lý lỗi | `exception/FacConfirmExceptionHandler.java` |

## 2. Field và công đoạn

Nguồn: `FacConfirmEditRules.FIELD_TO_DB_PROCESS`, `FIELD_LABELS`, `DEFAULT_OWNER_PROCESS`, `getOwnerProcess`.

| field | Tên hiển thị | `ProcessGrp` (DB) | Cột trong `F2_Backlog_Main` | Công đoạn sở hữu |
|---|---|---|---|---|
| `toDrill` | To Drill | `To Drill` | `ToDrill` | Rough |
| `toHeat` | To Heat | `To Heat` | `ToHeat` | Rough |
| `heatStart` | Heat Start | `Heat Start` | `TimeSQuenching` | Heat (chỉ để xem) |
| `heatFinish` | **To CLG** | `Heat Finish` | `TimeFHeat` | Heat; **Rough** với dòng không có Heat |
| `toPk` | To Packing | `To Packing` | `ToPK` | Fine |

Giá trị hiển thị của mỗi ô = `COALESCE(<cột F2_Backlog_Main>, <bản ghi mới nhất trong F2_Backlog_Fac_Confirm>)`, tức **dữ liệu Backlog được ưu tiên** (`FacConfirmRepository.FAC_DATA_CTE`, CTE `LatestConfirm` / `ConfirmPivot` / `FacData`).

## 3. Quy tắc nghiệp vụ

Tất cả nằm trong `repository/facconfirm/FacConfirmEditRules.java`, trừ khi ghi khác.

### 3.1. Điều kiện "không có Heat"

Hằng số `FacConfirmEditRules.NO_HEAT_CONDITION` (trên `F2_Backlog_Main`, alias `bl`):

```sql
UPPER(LTRIM(RTRIM(ISNULL(bl.Heat_Note, '')))) = 'NO HEAT'
AND ISNULL(bl.WaitingDays, -1) NOT IN (2, 5, 7)
```

- Ngoại lệ WaitingDays 2 / 5 / 7: cột Heat Note (`FacData.Note`) ưu tiên hiển thị `TD chờ 2 ngày` / `DC53 chờ 5 ngày` / `Molypden chờ 7 ngày` trước `Không có Heat`. Điều kiện gốc theo đúng thứ tự đó để `hasHeatProcess` và Heat Note luôn khớp nhau.
  > TODO: cần xác nhận: dòng có `Heat_Note = 'NO HEAT'` nhưng `WaitingDays` = 2/5/7 có thực sự cần Heat không, hay là dữ liệu sai? Hiện BE coi là **có Heat**.
- Những chỗ dùng điều kiện này:

| Dùng ở | Vị trí |
|---|---|
| `FacData.IsNoHeatNote`, `HasHeatProcess`, Heat Note "Không có Heat" | `FacConfirmRepository.FAC_DATA_CTE` |
| Lọc khi chọn Heat | `FacConfirmRepository.buildBaseWhere` |
| Thẻ tổng hợp | `FacConfirmRepository.findProcessGroups` (qua `finalProcessSql`) |
| Kiểm tra quyền khi lưu | `FacConfirmProcessTimeRepository.findNoHeatNoteAufnrs` |
| `ownerProcess` của confirmed-processes | `FacConfirmProcessTimeRepository.findConfirmedProcesses` |

### 3.2. Quyền sửa ô (công đoạn × loại dòng)

`FacConfirmEditRules.getEditableFields(row, procGrp)` (dựa trên `DEFAULT_EDITABLE_FIELDS` và `ROUGH_NO_HEAT_FIELDS`):

| Công đoạn | Dòng thường | Dòng không có Heat |
|---|---|---|
| Rough | `toDrill`, `toHeat` | `toDrill`, `heatFinish` (quy tắc "rough-no-heat") |
| Heat | `heatFinish` | (không có, vì dòng không thuộc Heat) |
| Fine | `toPk` | `toPk` |

- **Công đoạn Heat chỉ xác nhận To CLG (`heatFinish`).** `heatStart` **chỉ để xem** ở mọi công đoạn (`READ_ONLY_FIELDS`, `isReadOnlyField`). Dữ liệu Heat Start vẫn trả về trong danh sách và export, bản ghi `Heat Start` cũ trong `F2_Backlog_Fac_Confirm` vẫn đọc và hiển thị bình thường.
- Khi request lưu không gửi `procGrp`, BE chấp nhận field được phép ở **ít nhất một** công đoạn (`getEditableFieldsAnyProcess`).

### 3.3. Ô thuộc công đoạn nào

`FacConfirmEditRules.getOwnerProcess(row, field)`: dùng `DEFAULT_OWNER_PROCESS` (bảng ở mục 2). Riêng dòng không có Heat thì `heatFinish` thuộc **Rough**. Bản ghi `Heat Start` cũ vẫn thuộc Heat khi hiển thị.

### 3.4. Thẻ tổng hợp: "Cần xác nhận" / "Đã xác nhận"

`FacConfirmRepository.findProcessGroups`, CTE `FilteredBase` → `ProcessScope` → `Summary`.

| Thẻ | Dòng được tính (`ProcessGrp2` của Backlog) | Process cuối, dòng thường | Process cuối, dòng không có Heat |
|---|---|---|---|
| Rough | `Rough` hoặc NULL | `To Heat` | `Heat Finish` (To CLG) |
| Heat | `Heat`, `Rough` hoặc NULL; **loại dòng không có Heat** | `Heat Finish` | (không tính) |
| Fine | `Fine`, `Heat`, `Rough` hoặc NULL | `To Packing` | `To Packing` |

- **Cần xác nhận** = số dòng (`RequiredOrderCount`) và tổng `FinalQty` (`RequiredTotalQty`).
- **Đã xác nhận** = các dòng có bản ghi `ProcessGrp` = process cuối, với `ConfirmFnTime IS NOT NULL`, trong `F2_Backlog_Fac_Confirm` (`ConfirmedOrderCount`, `ConfirmedTotalQty`).
- Process cuối: `FacConfirmEditRules.getFinalProcess(row, procGrp)`, là field cuối cùng trong `getEditableFields`. SQL được sinh từ đó qua `finalProcessSql` và `finalProcessesSqlList`.
- Chỉ xét bảng `F2_Backlog_Fac_Confirm`; **không** xét giá trị đã có sẵn trong `F2_Backlog_Main`.
  > TODO: cần xác nhận: PO đã có `ToHeat` / `TimeFHeat` / `ToPK` từ hệ thống Backlog nhưng không có bản ghi Fac Confirm thì hiện bị tính là **chưa xác nhận**. Đây có phải ý muốn không?
- Thẻ Rough chỉ xét process cuối, không yêu cầu có `To Drill`.
  > TODO: cần xác nhận: Rough có cần đủ cả To Drill + process cuối mới tính "Đã xác nhận" không?

### 3.5. Điều kiện lọc chung (danh sách, count, filter options, export)

`FacConfirmRepository.buildBaseWhere` (thẻ tổng hợp dùng điều kiện tương tự, nhưng không có `procGrp`):

| Điều kiện | SQL / nguồn |
|---|---|
| Ngày xuất | `d.ExportD <= expD` |
| Công đoạn | Fine: `ProcessGrp2 IN (Fine, Heat, Rough)` hoặc NULL; Heat: `IN (Heat, Rough)` hoặc NULL; Rough: `= Rough` hoặc NULL |
| Heat | `procGrp = Heat` thì loại dòng không có Heat (`FacConfirmEditRules.hasHeatSql`) |
| Div | `d.Div = div`, hoặc `div = 'GU'` thì `d.Div LIKE '%G'` |
| Không cần Fac Confirm | `d.IsNoCount = 0`. `IsNoCount = 1` khi: `ProductGrp = 'Cam'`, `FERTH LIKE 'Backing Plug%'`, `PHCD LIKE 'J%'`, `ProcessGrp2 = 'MTO'`, (`ZGLOBAL_CODE IS NULL` và `PRT_ADDCMT2 NOT LIKE '%FNK%'`), hoặc `Status2 <> 'ON PROGRESS'` |
| `classify` | `Sale`: `Classify = 'Sale'`; `Stock`: `Classify <> 'Sale'` |
| `heatType` | `Normal`: không DC53/TD/Molypden; `DC53`: `WaitingDays = 5`; `TD`: `= 2`; `Molypden`: `= 7` |

> TODO: cần xác nhận ý nghĩa nghiệp vụ của `div = 'GU'` → `Div LIKE '%G'` và các điều kiện `IsNoCount` (code chỉ có comment "PO KHÔNG CẦN FAC CONFIRM").

### 3.6. Validate thời gian

`FacConfirmEditRules.validateTimes` (gọi từ `FacConfirmProcessTimeService.validateTimes`), lỗi trả 400, message tiếng Việt.

| Quy tắc | Chi tiết | Vị trí |
|---|---|---|
| Thứ tự, dòng thường | To Drill ≤ To Heat ≤ Heat Start ≤ To CLG ≤ To Packing | `NORMAL_TIME_ORDER`, `getTimeOrder` |
| Thứ tự, dòng không có Heat | To Drill ≤ To CLG ≤ To Packing | `NO_HEAT_TIME_ORDER`, `getTimeOrder` |

- **Không giới hạn thời gian ở tương lai (theo yêu cầu nghiệp vụ).** Không thêm lại kiểm tra này.
- So sánh với giá trị đang hiển thị (Backlog ưu tiên, sau đó Fac Confirm mới nhất; `FacConfirmEditState.currentValues`), cộng với các thay đổi khác trong cùng request.
- Chỉ báo lỗi cho cặp có ít nhất một ô đang được sửa; dữ liệu cũ sai thứ tự mà không sửa thì không bị chặn.
- So sánh dùng `LocalDateTime` (không có múi giờ): FE và server phải cùng giờ địa phương.
- Ví dụ: `Thời gian không hợp lệ: PO 123456: To CLG (08/10/2026 10:00) không được trước To Drill (08/10/2026 11:00)`.

### 3.7. Ô có sẵn dữ liệu Backlog

- Hiển thị: ô ưu tiên giá trị `F2_Backlog_Main` (mục 2).
- `/confirmed-processes` chỉ trả bản ghi Fac Confirm của process mà cột tương ứng trong `F2_Backlog_Main` **đang NULL** (`FacConfirmProcessTimeRepository.findConfirmedProcesses`).
- API lưu **từ chối** ô đã có giá trị trong `F2_Backlog_Main` (`FacConfirmEditRules.isLockedByBacklog`, cột theo `FIELD_TO_BACKLOG_COLUMN`), lỗi 400: `Ô đã có dữ liệu từ Backlog, không xác nhận lại được: PO 123456: To Drill = 08/10/2026 09:00`.

### 3.8. Thêm quy tắc mới

1. Sửa `FacConfirmEditRules`:
   - Field mới: thêm hằng số field, `FIELD_TO_DB_PROCESS`, `FIELD_TO_BACKLOG_COLUMN`, `FIELD_LABELS`, `DEFAULT_OWNER_PROCESS`, và vị trí trong `NORMAL_TIME_ORDER` / `NO_HEAT_TIME_ORDER`.
   - Quyền sửa: sửa `getEditableFields` (thứ tự field quan trọng: field **cuối** là process cuối của công đoạn).
   - Field chỉ để xem: `READ_ONLY_FIELDS`.
   - Điều kiện dòng mới: thêm thuộc tính vào `EditRow`, cột cờ vào `FacData` trong `FacConfirmRepository.FAC_DATA_CTE`, và hàm sinh SQL tương tự `isNoHeatSql`.
2. Field mới còn phải thêm vào: `FacConfirmDto`, `FacConfirmRowMapper`, `FacConfirmColumnMetadataProvider`, `FacConfirmFilterField`, danh sách cột trong `FacConfirmExcelService`, và cột `FacData` / `ConfirmPivot` / `findConfirmedProcesses`.
3. Không viết lại điều kiện "không có Heat" hay process cuối ở chỗ khác; gọi qua `FacConfirmEditRules`.
4. Thêm test vào `src/test/java/.../repository/facconfirm/FacConfirmEditRulesTest.java`.
5. Đồng bộ FE: `backlog-web/src/config/facConfirmEditRules.ts`.

## 4. API

Base path: `/api/fac-confirm`. Tất cả lỗi trả về `{ "status": <code>, "message": "..." }` (`FacConfirmExceptionHandler`):

| Loại lỗi | Status | message |
|---|---|---|
| Validate / quyền sửa | 400 | message nghiệp vụ |
| Thiếu tham số query | 400 | `<tên> is required` |
| Sai kiểu tham số | 400 | `Invalid value for <tên>` |
| JSON hỏng | 400 | `Invalid request body` |
| Lỗi khác (SQL…) | 500 | message chung; chi tiết chỉ ghi log |

Lỗi validate dùng chung cho các API có tham số này (`FacConfirmService`):

| Điều kiện | message |
|---|---|
| thiếu `div` / `expD` / `procGrp` | `div is required` / `expD is required` / `procGrp is required` |
| `procGrp` sai | `Invalid procGrp: X. Allowed values: Fine, Heat, Rough` |
| `classify` sai | `classify must be Sale or Stock` |
| `heatType` sai | `heatType must be All, Normal, DC53, TD or Molypden` |
| quá 50 filter | `Too many filters. Maximum allowed: 50` |
| filter thiếu field / operator, field không hỗ trợ | `Filter field is required` / `Filter operator is required for field: X` / `Unsupported Fac Confirm filter field: X` |

`FacConfirmFilterItem`: `{ "field", "operator", "value", "values": [] }`. Operator hỗ trợ (`FacConfirmFilterSqlBuilder`):
- TEXT: `contains`, `doesnotcontain`, `equals`/`is`/`=`, `doesnotequal`/`not`/`!=`, `startswith`, `endswith`, `isempty`, `isnotempty`, `in`/`isanyof`.
- NUMBER: `=`, `!=`, `>`, `>=`, `<`, `<=`, `isempty`, `isnotempty`, `in`/`isanyof`.
- DATE: `is`, `not`, `after`, `onorafter`, `before`, `onorbefore`, `isempty`, `isnotempty`, `in`/`isanyof`. Ngày dạng `yyyy-MM-dd`.

### 4.1. `GET /api/fac-confirm`: danh sách (API cũ, không có filter)

| Tham số | Kiểu | Bắt buộc | Ý nghĩa |
|---|---|---|---|
| `div` | string | có | Div |
| `expD` | date `yyyy-MM-dd` | có | Lấy `ExportD <= expD` |
| `procGrp` | `Rough`/`Heat`/`Fine` | có | Công đoạn |
| `classify` | `Sale`/`Stock` | không | |
| `heatType` | `All`/`Normal`/`DC53`/`TD`/`Molypden` | không (mặc định `All`) | |
| `page` | int | không (mặc định 0) | |
| `size` | int | không (mặc định 100, tối đa 200) | |

Response: `PageResponse<FacConfirmDto>`

```json
{
  "content": [{
    "ferth": "...", "productGrp": "...", "aufnr": "123456", "zglobalCode": "...", "pname": "...",
    "issueD": "...", "exportD": "...", "cusId": "...", "shipBy": "...", "mtoId": "...",
    "prtAddcmt2": "...", "currentProcess": "...", "finalQty": 10,
    "waitingDays": 5, "hasHeatProcess": true, "isDC53": true, "isTD": false, "isMolypden": false,
    "note": "DC53 chờ 5 ngày",
    "toDrill": "...", "toHeat": "...", "heatStart": "...", "heatFinish": "...", "toPk": null
  }],
  "page": 0, "size": 100, "totalElements": 1, "totalPages": 1, "first": true, "last": true
}
```
Quy tắc áp dụng: 3.1, 3.5.

### 4.2. `POST /api/fac-confirm/search`: danh sách có filter / tìm kiếm

Body `FacConfirmSearchRequest`:

| Tên | Kiểu | Bắt buộc | Ý nghĩa |
|---|---|---|---|
| `div`, `expD`, `procGrp` | | có | như 4.1 |
| `classify`, `heatType` | | không | như 4.1 |
| `search` | string | không | `LIKE %x%` trên FERTH, ProductGrp, AUFNR, ZGLOBAL_CODE, PNAME, CusId, ShipBy, MTO_ID, PRT_ADDCMT2, CurrentProcess, Note |
| `page` | int | không (mặc định 0) | |
| `size` | int | không (mặc định 20, tối đa 200) | |
| `filters` | `FacConfirmFilterItem[]` | không | tối đa 50 |
| `logicOperator` | `and`/`or` | không (mặc định `and`) | cách nối các filter |

Response: như 4.1. `totalElements` đếm cùng điều kiện (`FacConfirmRepository.countSearch`). Quy tắc áp dụng: 3.1, 3.5.

### 4.3. `POST /api/fac-confirm/filter-options`: giá trị cho menu lọc

Body `FacConfirmFilterOptionsRequest`: `field` (bắt buộc, phải được hỗ trợ), `search`, `div`, `expD`, `procGrp`, `classify`, `heatType`, `filters`.

Response: `["", "2026-10-01", "ABC"]`, gồm các giá trị DISTINCT. Ngày dạng `yyyy-MM-dd`, NULL thành `""`. Filter của chính `field` bị bỏ qua; các filter khác nối bằng `and`.

Quy tắc áp dụng: 3.5 (chọn Heat thì không còn giá trị `Không có Heat`). Lỗi: `field is required`, `Unsupported Fac Confirm filter field: X`.

### 4.4. `POST /api/fac-confirm/process-groups`: thẻ tổng hợp Rough / Heat / Fine

Body `FacConfirmProcessGroupRequest`: `div`, `expD` (bắt buộc), `classify`, `heatType`, `filters`, `logicOperator`. Không có `procGrp` vì tính cả 3 thẻ cùng lúc.

```json
[
  { "processGroup": "Rough", "requiredOrderCount": 120, "requiredTotalQty": 560.00, "confirmedOrderCount": 80, "confirmedTotalQty": 400.00 },
  { "processGroup": "Heat",  "...": "..." },
  { "processGroup": "Fine",  "...": "..." }
]
```
Thẻ không có dòng nào thì không xuất hiện trong mảng. Quy tắc áp dụng: 3.4, 3.5.

### 4.5. `POST /api/fac-confirm/confirmed-processes`: các ô đã được xác nhận qua Fac Confirm

Body: mảng `aufnr`, ví dụ `["123456", "123457"]`. Rỗng thì trả `[]`.

```json
[
  { "aufnr": "123456", "processGrp": "Heat Finish", "confirmFnTime": "2026-10-01T08:30:00",
    "updater": "PC-F2-001_22847", "updatedAt": "2026-10-01T08:31:00", "ownerProcess": "Rough" }
]
```
- Chỉ trả bản ghi mà cột tương ứng trong `F2_Backlog_Main` đang NULL (mục 3.7).
- `processGrp` là tên process trong DB; `ownerProcess` là công đoạn sở hữu (mục 3.3).

### 4.6. `PATCH /api/fac-confirm/process-times`: lưu thời gian xác nhận

Body `FacConfirmProcessTimeRequest`:

| Tên | Kiểu | Bắt buộc | Ý nghĩa |
|---|---|---|---|
| `employeeId` | string | có | Mã nhân viên, ghép vào `Updater` |
| `procGrp` | `Rough`/`Heat`/`Fine` | không | Có thì kiểm tra quyền theo đúng công đoạn |
| `changes` | array | có, tối đa 500 | |
| `changes[].aufnr` | string | có | |
| `changes[].field` | `toDrill`/`toHeat`/`heatStart`/`heatFinish`/`toPk` | có | |
| `changes[].value` | datetime ISO | có | |

Xử lý (`FacConfirmProcessTimeService.save`, `@Transactional`):
1. Validate toàn bộ request; có một lỗi là không lưu gì.
2. `heatStart` bị từ chối ở mọi công đoạn.
3. Đọc trạng thái từng PO một lần (`FacConfirmProcessTimeRepository.findEditStates`): cờ không có Heat, giá trị Backlog, bản ghi Fac Confirm mới nhất.
4. Kiểm tra quyền theo `getEditableFields` (mục 3.2).
5. Từ chối ô đã có dữ liệu Backlog (mục 3.7).
6. Kiểm tra thời gian (mục 3.6).
7. Ghi vào `F2_Backlog_Fac_Confirm` theo (`AUNFR`, `ProcessGrp`) bằng `MERGE ... WITH (HOLDLOCK)` (`upsert`): `ConfirmFnTime`, `Updater = <tên máy>_<employeeId>`, `UpdatedAt = SYSDATETIME()`.

Response:
```json
{ "updated": 2, "clientIp": "10.0.0.5", "machineName": "PC-F2-001" }
```

Lỗi 400:

| message |
|---|
| `Request is required`, `Employee ID is required`, `No process changes to save`, `Maximum 500 changes per request` |
| `AUFNR is required`, `Invalid Fac Confirm field: X`, `Confirm time is required` |
| `Invalid procGrp: X. Allowed values: Rough, Heat, Fine` |
| `Heat Start chỉ để xem, không xác nhận được: <AUFNR>, ...` |
| `Field not editable in Rough: 123456 (To Heat, Heat Note "Không có Heat"), ...` (không gửi `procGrp` thì không có phần `in Rough`) |
| `Ô đã có dữ liệu từ Backlog, không xác nhận lại được: PO 123456: To Drill = 08/10/2026 09:00; ...` |
| `Thời gian không hợp lệ: PO 123456: To CLG (...) không được trước To Drill (...); ...` |


### 4.7. `POST /api/fac-confirm/export/excel`: xuất Excel

Body `FacConfirmExcelExportRequest`: `div`, `expD`, `procGrp` (bắt buộc), `classify`, `heatType`, `search`, `filters`, `logicOperator`, `columns` (bắt buộc, danh sách key cột, giữ thứ tự).

- `columns` nhận key dạng DataGrid (`toDrill`, `heatFinish`, ...) hoặc tên cột (`ToDrill`, `Heat_Finish`, ...). Tiêu đề cột do BE đặt (`FacConfirmExcelService`, danh sách `ExcelColumn`), ví dụ `Heat_Finish` → **To CLG**.
- Response: file `fac_confirm_yyyyMMdd_HHmmss.xlsx` (stream), sheet `Fac Confirm`, có phần thông tin báo cáo + filter ở đầu sheet.
- Validate trước khi ghi file (`FacConfirmExcelService.validateExportRequest`, trả về `ExportPlan`), nên mọi lỗi đều là 400 JSON:
  - Tham số lọc và filter: dùng chung `FacConfirmService.normalizeQuery` với `/search` (cùng lỗi như bảng lỗi chung ở trên).
  - Cột: `At least one export column is required`, `Unsupported export column: X`, `Duplicate export column: X`.
  - Giá trị filter sai (ngày / số) và số dòng: chạy `countSearch` trước khi ghi file.
- Giới hạn: tối đa `FacConfirmExcelService.MAX_EXPORT_ROWS` = 1.048.570 dòng dữ liệu (giới hạn của Excel). Vượt giới hạn: 400 `Dữ liệu export có N dòng, vượt giới hạn 1048570 dòng. Hãy thu hẹp bộ lọc.` Lúc ghi file vẫn chặn theo cùng hằng số, phòng dữ liệu tăng giữa lúc đếm và lúc ghi.
- Quy tắc áp dụng: 3.1, 3.5.

### 4.8. `GET /api/fac-confirm/debug-client`: debug (chỉ profile `dev`)

Trả `remoteAddr`, `xForwardedFor`, `xRealIp`, `resolvedClientIp`, `machineName` để kiểm tra cách BE nhận diện máy client (`ClientMachineService`).

- Chỉ bật khi chạy với profile `dev` (`--spring.profiles.active=dev`): `FacConfirmDebugController` có `@Profile("dev")`.
- Profile khác (production): endpoint không được đăng ký, trả 404.

## 5. Bảng dữ liệu

| Bảng | Đọc / ghi | Cột chính | Dùng ở |
|---|---|---|---|
| `F2_Backlog_Main` | Đọc | `AUFNR`, `FERTH`, `ProductGrp`, `ZGLOBAL_CODE`, `PNAME`, `IssueD`, `ExportD`, `RRONYU1` (CusId), `ShipBy`, `MTO_ID`, `PRT_ADDCMT2`, `CurrentProcess`, `FinalQty`, `Classify`, `ProcessGrp2`, `Div`, `WaitingDays`, `Heat_Note`, `PHCD`, `Status2`, `ToDrill`, `ToHeat`, `TimeSQuenching`, `TimeFHeat`, `ToPK` | `FAC_DATA_CTE`, `findConfirmedProcesses`, `findEditStates` |
| `F2Database.dbo.F2_Backlog_Fac_Confirm` | Đọc + ghi | `AUNFR` (mã PO, chú ý viết `AUNFR`), `ProcessGrp`, `ConfirmFnTime`, `Updater`, `UpdatedAt` | `FAC_DATA_CTE` (lấy bản ghi mới nhất theo `UpdatedAt` cho mỗi `AUNFR` + `ProcessGrp`), `findProcessGroups`, `findConfirmedProcesses`, `findEditStates`, `upsert` |

Không dùng view. `FacData` là CTE trong `FacConfirmRepository.FAC_DATA_CTE`.

Script unique index (`AUNFR`, `ProcessGrp`): `sql/fac_confirm_unique_index.sql`. **Chưa chạy**: chạy sau khi đã dọn bản ghi trùng; script tự dừng nếu vẫn còn trùng.

## 6. Lưu ý và hạn chế đã biết

- **Chưa có xác thực bằng token**: `employeeId` do FE gửi lên và không được kiểm tra với tài khoản đăng nhập. `Updater` = tên máy (reverse DNS từ IP, qua `X-Forwarded-For` / `X-Real-IP` / remote address) + `_` + `employeeId`.
- `@CrossOrigin(origins = "*")` trên `FacConfirmController`.
- Có hai danh sách field filter: `FacConfirmFilterField` (dùng để validate trong `FacConfirmService`) và `FacConfirmColumnMetadataProvider` (dùng để sinh SQL). Hàm `FacConfirmFilterField.column` (map sang `bl.*`) không được dùng.
- `FacConfirmProcessTimeResponse` không được dùng; API lưu trả `Map` (mục 4.6).
- `upsert` dùng `MERGE ... WITH (HOLDLOCK)`, nên request mới không tạo bản ghi trùng. Dữ liệu cũ có thể đã trùng (`IF EXISTS … UPDATE … ELSE INSERT` trước đây không khóa): khi đọc, BE lấy bản ghi có `UpdatedAt` mới nhất; khi lưu, `MERGE` cập nhật mọi bản ghi trùng của cặp đó. Kiểm tra bằng bước 1 của `sql/fac_confirm_unique_index.sql`.
- Không tìm thấy comment `TODO` / `FIXME` nào trong code Java.
