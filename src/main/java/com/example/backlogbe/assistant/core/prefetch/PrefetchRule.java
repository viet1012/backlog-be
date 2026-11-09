package com.example.backlogbe.assistant.core.prefetch;

import com.example.backlogbe.assistant.core.api.dto.ToolCall;

import java.util.List;
import java.util.Map;

// =========================================================
// QUY TẮC PRE-FETCH TOOL
//
// Câu hỏi khớp quy tắc -> ChatOrchestrator tự chạy các tool trong calls() TRƯỚC khi gọi LLM,
// nối kết quả thành công vào cuối context: "[Kết quả <tool> đã tra sẵn]\n<kết quả>",
// và đưa tool vào sources của response như tool model đã gọi.
// Tool phải thuộc ScreenModule.tools() của màn hình; không có thì bỏ qua.
// Khớp nhưng không call nào thành công -> nối fallbackHint(lang) (nếu có) vào cuối câu hỏi.
// Thêm quy tắc: implement interface này và trả về trong ScreenModule.prefetchRules().
// =========================================================
public interface PrefetchRule {

	// question: câu hỏi gốc của người dùng (đã trim, chưa thêm lưu ý hệ thống)
	boolean matches(String question, String screenKey);

	// Như trên, kèm params màn hình (tháng đang xem) để quy tắc dựa vào trạng thái màn hình (vd ô đang mở).
	// ChatOrchestrator gọi hàm này; mặc định bỏ qua params.
	default boolean matches(String question, String screenKey, Map<String, String> params) {
		return matches(question, screenKey);
	}

	// arguments của ToolCall: chuỗi JSON object, như tool call của model
	List<ToolCall> calls(String question, Map<String, String> params);

	// Câu nhắc nối vào cuối câu hỏi khi khớp nhưng pre-fetch không ra kết quả (vd "(Lưu ý hệ thống: gọi … )");
	// null = không nhắc
	default String fallbackHint(String lang) {
		return null;
	}
}
