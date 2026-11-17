# CLAUDE.md

Hướng dẫn cho AI coding agent làm việc trong repo `backlog-be` (Spring Boot, Java).

## Quy ước làm việc

- Trả lời bằng tiếng Việt.
- Không viết/chạy test. Không build (`mvn`, `gradle`…) trừ khi được yêu cầu rõ.
- Không đọc/in/ghi mật khẩu, API key trong `application*.properties`. Khi cần sửa cấu hình trợ lý,
  chỉ thay đúng dòng `assistant.*` (Edit theo dòng, không mở/dump cả file).
- Chỗ không chắc: hỏi lại, hoặc ghi rõ "(cần xác nhận)" — không đoán.
- Chỉ làm việc trong repo này.

## Trợ lý AI

Chi tiết: [docs/assistant/README.md](docs/assistant/README.md).

### Kiến trúc (tóm tắt)

- Luồng chat (`core/chat/ChatOrchestrator`): validate → `ScreenModule.answerGuide` có `directAnswer` (template,
  `assistant.template-answers.enabled`) thì trả luôn, không gọi LLM; không thì → context theo `screenKey` (`ScreenModule.buildContext`)
  → phát hiện tháng trong câu hỏi (`core/month`) → pre-fetch tool (`PrefetchRule`)
  → `ScreenModule.answerGuide` (khối kết luận tính sẵn trước "Câu hỏi:", đủ dữ liệu thì `tool_choice: none`) → prompt (`core/prompt`)
  → LLM + tool calling (`core/llm`) → hậu xử lý (`core/postprocess/AnswerPostProcessor`: thay mã nội bộ, định dạng số).
- Câu hỏi khoảng nhiều tháng ("trong 3 tháng", "quý này", "từ tháng 8 đến 10"…): `QuestionMonthDetector.range` → `ScreenModule.answerGuideForRange`
  (chỉ nhận `directAnswer` tính theo cả khoảng) → không có thì LLM + `rangeNote`. Không trả template / kết luận 1 tháng cho câu hỏi khoảng.
  Cost Monitoring: truy vấn các khung chạy song song, giới hạn 45 s (quá hạn → "đang tổng hợp, hỏi lại sau", tính tiếp ở nền);
  tháng không có Actual phải ghi "chưa có dữ liệu", không bỏ qua im lặng.
- `assistant/core/`: lõi dùng chung — api, chat, llm, prompt, month, prefetch, postprocess, suggestion, tool, eval.
- `assistant/core/screen/`: `ScreenModule` (hợp đồng mỗi màn hình), `ScreenRegistry`, `ParamSpec`.
- `assistant/screens/{screen}/`: module riêng từng màn hình (tool, context provider, Assessment, snapshot, repository).
  Mẫu: `screens/_template/`.
- `resources/assistant/core/common.md`: kiến thức chung; `resources/assistant/screens/{screenKey}/`:
  `knowledge.md`, `rules.md`, `codes.json`, `suggestions.json`, `eval.json` (mẫu ở `_template/`).

### Quy tắc khi sửa AI

- Lõi (`core/`) không chứa từ khóa nghiệp vụ (tên tool, chỉ số, bộ phận…). Logic riêng màn hình đặt trong
  `screens/{screenKey}/` (Java) và `resources/assistant/screens/{screenKey}/`.
- Ưu tiên tính kết luận bằng code (lớp `*Assessment`) thay vì để model tự so sánh số.
  Intent mà kết luận tính sẵn trả lời trọn (vd Cost Monitoring: tổng quan, xếp hạng, dự báo) → render câu trả lời bằng
  code (`AnswerGuide.directAnswer` + `suggestions`), không gọi LLM; tắt bằng `assistant.template-answers.enabled=false`.
  Chỉ dùng template khi phân loại intent CHẮC CHẮN (câu hỏi so sánh / đánh giá / mơ hồ / khớp nhiều intent → LLM);
  ghi cách phân loại vào `AnswerGuide.intentMatch` (audit `intentMatch`).
- Kết quả tool / context: dạng `Nhãn=giá trị`, số có dấu (`+1.2`, `-0.5`), nhãn tiếng Việt, không lộ mã nội bộ.
- Đổi hành vi trả lời: sửa `knowledge.md` / `rules.md` / `codes.json` trước; chỉ sửa Java khi tài nguyên không đủ.
- Số hiển thị (chênh lệch, %, tổng…) phải tính từ giá trị đã làm tròn 1 chữ số, để model và người đọc thấy khớp.
- System prompt chỉ chứa phần cố định (rules / common / knowledge, mô tả tool cố định, `Reasoning: low` cố định) để dùng KV cache; dữ liệu động
  (context, ngày, kết quả pre-fetch) nằm ở user message cuối (`PromptBuilder.buildUser`).
- Tên mascot: không viết cứng; trong `.md` dùng `{{assistantName}}`, trong Java dùng `AssistantProperties.resolvedName()` (`assistant.name`).
- Log chung không ghi câu hỏi, câu trả lời, params, tham số tool (chỉ screenKey, số lần gọi LLM, tên tool, thời gian, lỗi).
  Ngoại lệ duy nhất: audit log riêng `assistant.audit` (`core/audit/AssistantAuditLogger`, file JSONL, xem README mục 10)
  ghi câu hỏi, câu trả lời, params, mã nhân viên. Không thêm nội dung này vào log khác; không ghi tên người dùng.

### Checklist thêm màn hình mới

Chi tiết: [mục 8 – Hướng dẫn thêm màn hình mới](docs/assistant/README.md#8-hướng-dẫn-thêm-màn-hình-mới).

1. Chọn `screenKey` (`[a-z0-9-]`, vd `machine-monitor`).
2. Sao chép `assistant/screens/_template/` → `assistant/screens/{tên}/`; hiện thực `ScreenModule` (params, context, tools, prefetch, suggestion).
3. Sao chép `resources/assistant/screens/_template/` → `resources/assistant/screens/{screenKey}/`; viết `knowledge.md`, `rules.md`, `codes.json`.
4. Thêm Assessment cho các kết luận cần so sánh; tool trả `Nhãn=giá trị`.
5. Thêm `suggestions.json` và `eval.json`; kiểm bằng `POST /api/assistant/eval?screenKey=...` (profile dev) khi được yêu cầu.
6. Không sửa `core/` trừ khi cần mở rộng hợp đồng chung (không thêm từ khóa nghiệp vụ).

### Khi thay đổi tính năng AI

- Cập nhật [docs/assistant/README.md](docs/assistant/README.md): mục liên quan + [mục 12 – Lịch sử thay đổi](docs/assistant/README.md#12-lịch-sử-thay-đổi).
- Cập nhật file này nếu quy ước thay đổi.
