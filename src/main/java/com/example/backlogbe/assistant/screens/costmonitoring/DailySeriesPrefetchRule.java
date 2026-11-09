package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.api.dto.ToolCall;
import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// =========================================================
// PRE-FETCH get_daily_series (câu hỏi theo ngày, để model trả lời ngay trong 1 lần gọi LLM)
//
// Điều kiện: screenKey = cost-monitoring, không phải câu hỏi khái niệm, câu hỏi theo ngày (isDailyQuestion):
//   "ngày nào", "hôm nào", "theo ngày", "từng ngày", "đột biến", "ngày <số>" / "<số>/<số>" (1 ngày cụ thể),
//   "chi nhiều / ít nhất" (không hỏi về vật tư / cost center / máy / category – đó là get_top_items);
//   en: which day, daily, by day, day by day, spike.
// Khung: đúng 1 (CostMonitoringQuestion.frame: câu hỏi, phần thiếu lấy từ ô drawer). OT bỏ qua (chưa có actual).
// Không ra kết quả -> fallbackHint nhắc model tự gọi get_daily_series.
// =========================================================
@Component
public class DailySeriesPrefetchRule implements PrefetchRule {

	public static final String TOOL = "get_daily_series";

	private static final List<String> DAILY_KEYWORDS = List.of(
			"ngày nào", "ngay nao", "hôm nào", "hom nao", "theo ngày", "theo ngay", "từng ngày", "tung ngay",
			"đột biến", "dot bien",
			"which day", "what day", "daily", "by day", "day by day", "spike"
	);

	// "chi nhiều nhất / ít nhất / cao nhất / thấp nhất" (có dấu / không dấu)
	private static final Pattern MOST_SPENT = Pattern.compile(
			"(?:chi|tốn|ton)\\s+(?:nhiều|nhieu|ít|it|cao|thấp|thap)\\s+(?:nhất|nhat)"
	);

	// 1 ngày cụ thể: "ngày 25", "ngày 25/10", "25/10"
	private static final Pattern SPECIFIC_DAY = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])(?:(?:ngày|ngay)\\s+\\d{1,2}|\\d{1,2}/\\d{1,2})(?![\\p{N}])"
	);

	// Hỏi theo vật tư / cost center / máy -> get_top_items, không phải theo ngày
	private static final List<String> ITEM_KEYWORDS = List.of(
			"vật tư", "vat tu", "cost center", "máy", "category", "item", "material", "machine"
	);

	private static final String HINT_VI = "(Lưu ý hệ thống: gọi get_daily_series cho khung được hỏi trước khi trả lời.)";
	private static final String HINT_EN = "(System note: call get_daily_series for the asked frame before answering.)";

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

		if (!isDailyQuestion(question)) {
			return List.of();
		}

		CostMonitoringFocus frame = CostMonitoringQuestion.frame(question, params);

		if (frame == null || "OT".equals(frame.cost())) {
			return List.of();
		}

		return List.of(new ToolCall(
				"prefetch-" + TOOL + "-1",
				TOOL,
				"{\"div\":\"" + frame.div() + "\",\"cost\":\"" + frame.cost() + "\"}"
		));
	}

	@Override
	public String fallbackHint(String lang) {
		return CmText.isEn(lang) ? HINT_EN : HINT_VI;
	}

	public static boolean isDailyQuestion(String question) {

		if (question == null || question.isBlank()) {
			return false;
		}

		String q = CostMonitoringQuestion.normalize(question);

		if (CostMonitoringQuestion.isConcept(q)) {
			return false;
		}

		if (CostMonitoringQuestion.containsAny(q, DAILY_KEYWORDS) || SPECIFIC_DAY.matcher(q).find()) {
			return true;
		}

		return MOST_SPENT.matcher(q).find() && !CostMonitoringQuestion.containsAny(q, ITEM_KEYWORDS);
	}
}
