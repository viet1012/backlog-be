package com.example.backlogbe.assistant.screens._template;

import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import com.example.backlogbe.assistant.core.screen.ParamSpec;
import com.example.backlogbe.assistant.core.screen.ScreenModule;
import com.example.backlogbe.assistant.core.suggestion.SuggestionCondition;
import com.example.backlogbe.assistant.core.tool.AssistantTool;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

// =========================================================
// MẪU MÀN HÌNH MỚI – SAO CHÉP, KHÔNG DÙNG TRỰC TIẾP
//
// Các bước thêm màn hình (vd screenKey "machine-monitor"):
//   1. Sao chép thư mục này thành screens/machinemonitor (đổi package, tên lớp).
//   2. Sao chép resources/assistant/screens/_template thành resources/assistant/screens/machine-monitor
//      (tên thư mục = screenKey), sửa nội dung từng file (xem README.md trong đó).
//   3. Đổi SCREEN_KEY, khai báo paramSpec, viết buildContext (gọi service sẵn có, KHÔNG query DB riêng nếu có service).
//   4. Thêm @Component (org.springframework.stereotype.Component) cho module, tool, prefetch rule.
//      Lớp mẫu này CỐ Ý không có @Component để không bị đăng ký.
//   5. Chạy eval (profile dev): POST /api/assistant/eval?screenKey=machine-monitor
//
// Lõi (core) không được biết gì về màn hình: mọi từ khóa nghiệp vụ, tên tool, quy tắc riêng
// nằm trong package màn hình và resources/assistant/screens/{screenKey}/.
// =========================================================
public class TemplateModule implements ScreenModule {

	// Trùng screenKey FE gửi; dạng ^[a-z0-9-]{1,50}$
	public static final String SCREEN_KEY = "template";

	private final TemplateContextProvider contextProvider;
	private final List<AssistantTool> tools;

	public TemplateModule(TemplateContextProvider contextProvider, ExampleTool exampleTool) {
		this.contextProvider = contextProvider;
		// Thứ tự trong list = thứ tự tool gửi cho LLM
		this.tools = List.of(exampleTool);
	}

	@Override
	public String screenKey() {
		return SCREEN_KEY;
	}

	// Param FE gửi (chat: request.params; suggestions: query param cùng tên). Sai định dạng chỉ log WARN.
	@Override
	public List<ParamSpec> paramSpec() {
		return List.of(
				ParamSpec.of("month", "^\\d{4}[-.]\\d{2}$", "Tháng đang xem yyyy-MM")
		);
	}

	// "Dữ liệu hiện tại" trong system prompt: dạng text gọn "Nhãn=giá trị|…", nhãn theo lang, không gửi mã nội bộ
	@Override
	public String buildContext(Map<String, String> params, String lang) {
		return contextProvider.buildContext(params, lang);
	}

	@Override
	public List<AssistantTool> tools() {
		return tools;
	}

	// Tự chạy tool trước khi gọi LLM khi câu hỏi khớp (xem core/prefetch/PrefetchRule). Không cần thì xóa.
	@Override
	public List<PrefetchRule> prefetchRules() {
		return List.of();
	}

	// Câu gợi ý động: trả điều kiện đang đúng (when khớp "dynamic[].when" trong suggestions.json).
	// null = chỉ câu tĩnh, không greeting.
	@Override
	public List<SuggestionCondition> suggestionConditions(Map<String, String> params) {
		return null;
	}

	@Override
	public String suggestionGreeting(List<SuggestionCondition> conditions, String lang) {
		return null;
	}

	// true nếu màn hình có "tháng đang xem" và muốn trả lời câu hỏi về tháng khác
	@Override
	public boolean supportsMonthQuestions() {
		return false;
	}

	// Chỉ dùng khi supportsMonthQuestions() = true: context rút gọn cho tháng được hỏi; null = tháng chưa có dữ liệu
	@Override
	public String buildCompactContext(YearMonth month, String lang) {
		return null;
	}
}
