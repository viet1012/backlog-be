# Mẫu tài nguyên cho màn hình mới của trợ lý

Sao chép cả thư mục này thành `resources/assistant/screens/{screenKey}/` (tên thư mục = `screenKey` của ScreenModule, vd `machine-monitor`).
Các file đều tùy chọn: thiếu file nào thì phần đó bị bỏ qua. Chỉ đọc khi khởi động, cho các `screenKey` có bean ScreenModule (thư mục `_template` không bao giờ được đọc).

| File | Dùng ở đâu | Ghi chú |
|---|---|---|
| `knowledge.md` | System prompt, sau `core/common.md` | Kiến thức nghiệp vụ: khái niệm, công thức, cách đánh giá, mô tả dữ liệu gửi kèm và từng tool, ví dụ hỏi–đáp. |
| `rules.md` | System prompt, nối ngay sau quy tắc chung (CORE_RULES trong PromptBuilder) | Mỗi dòng 1 quy tắc dạng `- ...`. Chỉ quy tắc riêng màn hình; quy tắc chung đã có trong lõi. |
| `codes.json` | Hậu xử lý câu trả lời (AnswerPostProcessor) | `{ "MA_NOI_BO": { "vi": "nhãn", "en": "label" } }`. Mã còn sót trong câu trả lời (từ đứng riêng) được thay bằng nhãn. Thứ tự trong file = thứ tự thay. |
| `suggestions.json` | `GET /api/assistant/suggestions` | `dynamic[].when` khớp `SuggestionCondition.when` do ScreenModule trả; `{div}` / `{cost}` được điền. `static` là các nhóm câu tĩnh. |
| `eval.json` | `POST /api/assistant/eval?screenKey=...` (profile dev) | Danh sách `{question, month, mustContain, mustNotContain}`; gọi LLM thật. |

Quy tắc viết:
- Lõi (`assistant/core`) không chứa từ khóa nghiệp vụ. Mọi tên tool, chỉ số, bộ phận… chỉ xuất hiện trong package màn hình và thư mục này.
- `knowledge.md` mô tả đúng nhãn mà context / tool gửi (vd "Badge=…", "[Top vật tư] cột=…"), để model đọc đúng.
- Quy tắc trong `rules.md` nên ngắn, có ví dụ cụ thể; tránh trùng với quy tắc chung.
