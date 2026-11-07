package com.example.backlogbe.assistant.core.screen;

import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import com.example.backlogbe.assistant.core.suggestion.SuggestionCondition;
import com.example.backlogbe.assistant.core.tool.AssistantTool;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// =========================================================
// 1 MÀN HÌNH CỦA TRỢ LÝ
//
// Thêm màn hình mới: sao chép screens/_template (Java + resources/assistant/screens/_template),
// đổi screenKey, đánh dấu @Component. ScreenRegistry tự gom mọi bean ScreenModule.
//
// Văn bản theo màn hình (đọc khi khởi động, thiếu file thì bỏ qua):
//   resources/assistant/screens/{screenKey}/knowledge.md   kiến thức nghiệp vụ (system prompt)
//   resources/assistant/screens/{screenKey}/rules.md       quy tắc riêng màn hình (nối sau quy tắc chung)
//   resources/assistant/screens/{screenKey}/codes.json     mã nội bộ -> nhãn vi/en (hậu xử lý câu trả lời)
//   resources/assistant/screens/{screenKey}/suggestions.json  câu gợi ý (tĩnh / động)
//   resources/assistant/screens/{screenKey}/eval.json      bộ câu hỏi kiểm tra (profile dev)
// Lõi (core) không chứa từ khóa nghiệp vụ: mọi thứ riêng màn hình đi qua interface này.
// =========================================================
public interface ScreenModule {

	// Trùng screenKey FE gửi lên (vd "cost-monitoring"); dạng ^[a-z0-9-]{1,50}$
	String screenKey();

	// Tham số màn hình hợp lệ (tên + định dạng). Dùng để:
	//   - controller /suggestions lấy đúng các query param này,
	//   - ScreenRegistry log WARN khi param lạ / sai định dạng (không chặn request).
	default List<ParamSpec> paramSpec() {
		return List.of();
	}

	// Context "Dữ liệu hiện tại" cho prompt. params không null; lang "vi" | "en"
	String buildContext(Map<String, String> params, String lang);

	// Tool model được gọi trên màn hình này (thứ tự giữ nguyên khi gửi cho LLM)
	default List<AssistantTool> tools() {
		return List.of();
	}

	// Quy tắc tự chạy tool trước khi gọi LLM
	default List<PrefetchRule> prefetchRules() {
		return List.of();
	}

	// Điều kiện cho câu gợi ý động. null = màn hình không có gợi ý động / greeting.
	// Tham số sai -> IllegalArgumentException (400); lỗi khác (vd DB) -> engine bỏ câu động, log WARN.
	default List<SuggestionCondition> suggestionConditions(Map<String, String> params) {
		return null;
	}

	// Câu chào theo các điều kiện đã tính; null = không có greeting
	default String suggestionGreeting(List<SuggestionCondition> conditions, String lang) {
		return null;
	}

	// Màn hình có khái niệm "tháng đang xem" (param "month"; không có = tháng hiện tại) và
	// cho phép trả lời câu hỏi về tháng khác (dựng thêm context rút gọn cho tháng được hỏi).
	default boolean supportsMonthQuestions() {
		return false;
	}

	// Context rút gọn cho tháng được hỏi (khác tháng đang xem). null = tháng đó chưa có dữ liệu.
	// Chỉ gọi khi supportsMonthQuestions() = true.
	default String buildCompactContext(YearMonth month, String lang) {
		return null;
	}

	// Params màn hình khi chạy tool cho tháng khác tháng đang xem (tham số tool "month").
	// Mặc định: giữ params, đổi "month". Màn hình có param phụ thuộc tháng thì override để bỏ / đổi.
	default Map<String, String> paramsForMonth(Map<String, String> params, YearMonth month) {

		Map<String, String> result = new HashMap<>(params);
		result.put("month", month.toString());

		return result;
	}
}
