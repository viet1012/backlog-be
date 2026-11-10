package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.api.dto.ToolCall;
import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// =========================================================
// PRE-FETCH get_top_items (câu hỏi vật tư / máy / cost center / Category -> 1 lần gọi LLM thay vì 2)
//
// Lý do (audit log 2026-11-09): model mất 1 vòng gọi LLM (3.7–14 s, ~12–15k token prompt) chỉ để chọn get_top_items.
// Điều kiện:
//   - screenKey = cost-monitoring, câu có từ vật tư / máy / cost center / Category / item / machine / material;
//   - không hỏi nguyên nhân (explain_variance lo), so sánh / tháng trước (compare_previous_month lo),
//     không phải câu hỏi theo ngày (get_daily_series lo);
//   - ExplainVariancePrefetchRule không có kế hoạch, hoặc chỉ là FRAME (câu hỏi từ drawer);
//   - khung: đúng 1 (CostMonitoringQuestion.frame), hoặc 1 bộ phận không nêu loại chi phí -> T&SL và RM.
//     Không xác định được bộ phận -> không pre-fetch (6 khung quá dài).
// Câu "vật tư … nhất / lớn / mắc" phần lớn đã trả bằng template (CostMonitoringLookupAnswers); rule này lo phần còn lại.
// =========================================================
@Component
public class TopItemsPrefetchRule implements PrefetchRule {

	public static final String TOOL = "get_top_items";

	// Nhãn intent ghi audit khi pre-fetch này đủ trả lời (CostMonitoringAnswerGuide)
	public static final String INTENT = "top_items_prefetch";

	private static final List<String> COSTS = List.of("T&SL", "RM");

	private static final Pattern DETAIL = CostMonitoringQuestion.word(
			"vat tu|ma vat tu|may|cost center|category|item|items|machine|machines|material|materials");
	private static final Pattern EXCLUDED = CostMonitoringQuestion.word(
			"vi sao|tai sao|nguyen nhan|ly do|do dau|why|cause|reason"
					+ "|so sanh|thang truoc|cung ky|compare|previous month|last month");

	private static final String HINT_VI = "(Lưu ý hệ thống: gọi get_top_items cho khung được hỏi trước khi trả lời.)";
	private static final String HINT_EN = "(System note: call get_top_items for the asked frame before answering.)";

	@Override
	public boolean matches(String question, String screenKey) {
		return matches(question, screenKey, Map.of());
	}

	@Override
	public boolean matches(String question, String screenKey, Map<String, String> params) {
		return CostMonitoringModule.SCREEN_KEY.equals(screenKey) && !calls(question, params).isEmpty();
	}

	@Override
	public List<ToolCall> calls(String question, Map<String, String> params) {

		Map<String, String> safeParams = params != null ? params : Map.of();

		if (!isTopItemsQuestion(question, safeParams)) {
			return List.of();
		}

		List<ToolCall> calls = new ArrayList<>();
		int i = 0;

		for (CostMonitoringFocus f : frames(question, safeParams)) {
			calls.add(new ToolCall(
					"prefetch-" + TOOL + "-" + (++i),
					TOOL,
					"{\"div\":\"" + f.div() + "\",\"cost\":\"" + f.cost() + "\"}"
			));
		}

		return calls;
	}

	@Override
	public String fallbackHint(String lang) {
		return CmText.isEn(lang) ? HINT_EN : HINT_VI;
	}

	// Câu hỏi chi tiết mà get_top_items trả lời trọn được (xem điều kiện ở đầu file)
	static boolean isTopItemsQuestion(String question, Map<String, String> params) {

		if (question == null || question.isBlank()) {
			return false;
		}

		String f = CostMonitoringIntentClassifier.fold(question);

		if (!DETAIL.matcher(f).find() || EXCLUDED.matcher(f).find()
				|| DailySeriesPrefetchRule.isDailyQuestion(question)) {
			return false;
		}

		ExplainVariancePrefetchRule.Plan plan = ExplainVariancePrefetchRule.plan(question, params);

		if (plan != null && plan.intent() != ExplainVariancePrefetchRule.Intent.FRAME) {
			return false;
		}

		return !frames(question, params).isEmpty();
	}

	private static List<CostMonitoringFocus> frames(String question, Map<String, String> params) {

		CostMonitoringFocus one = CostMonitoringQuestion.frame(question, params);

		if (one != null) {
			return COSTS.contains(one.cost()) ? List.of(one) : List.of();
		}

		String q = CostMonitoringQuestion.normalize(question);
		List<String> divs = CostMonitoringQuestion.divs(q);

		if (divs.size() != 1 || !CostMonitoringQuestion.costs(q).isEmpty()) {
			return List.of();
		}

		return COSTS.stream().map(cost -> new CostMonitoringFocus(divs.get(0), cost)).toList();
	}
}
