package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.api.dto.ToolCall;
import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// =========================================================
// PRE-FETCH get_daily_series (câu hỏi theo ngày, để model trả lời ngay trong 1 lần gọi LLM)
//
// Điều kiện: screenKey = cost-monitoring, không phải câu hỏi khái niệm, câu hỏi theo ngày (isDailyQuestion):
//   "ngày nào", "hôm nào", "theo ngày", "từng ngày", "đột biến", "ngày <số>" / "<số>/<số>" (1 ngày cụ thể),
//   "hôm qua", "hôm kia", "chủ nhật", "thứ hai … thứ bảy" (có dấu),
//   "chi nhiều / ít nhất" (không hỏi về vật tư / cost center / máy / category – đó là get_top_items);
//   "khi nào / bao giờ / lúc nào (sẽ) vượt" (ngày dự kiến vượt Fluct theo Estimate);
//   en: which day, daily, by day, day by day, spike, "when … exceed / over".
// Khung:
//   đúng 1 (CostMonitoringQuestion.frame: câu hỏi, phần thiếu lấy từ ô drawer) -> month_end=true (đến hết tháng)
//   ≥ 2 khung nêu rõ trong câu (bộ phận × T&SL / RM, tối đa 6) -> mỗi khung 1 lần, month_end=false
//     (đến ngày D + dòng tóm tắt Estimate cuối tháng / ngày dự kiến vượt)
//   OT bỏ qua (chưa có actual).
// Không ra kết quả -> fallbackHint nhắc model tự gọi get_daily_series.
// =========================================================
@Component
public class DailySeriesPrefetchRule implements PrefetchRule {

	public static final String TOOL = "get_daily_series";

	private static final List<String> DAILY_KEYWORDS = List.of(
			"ngày nào", "ngay nao", "hôm nào", "hom nao", "theo ngày", "theo ngay", "từng ngày", "tung ngay",
			"đột biến", "dot bien", "xu hướng chi", "xu huong chi", "diễn biến", "dien bien",
			// 1 ngày gọi theo tên (audit 2026-11-10: "Chủ nhật vừa rồi…" bị hiểu là xếp hạng)
			"hôm qua", "hom qua", "hôm kia", "hom kia", "chủ nhật", "chu nhat",
			"thứ hai", "thứ ba", "thứ tư", "thứ năm", "thứ sáu", "thứ bảy",
			// Ngày dự kiến vượt Fluct (cột Estimate (MTD) + dòng "Ngày dự kiến vượt Fluct")
			"khi nào vượt", "khi nao vuot", "bao giờ vượt", "bao gio vuot", "lúc nào vượt", "luc nao vuot",
			"khi nào sẽ vượt", "khi nao se vuot", "bao giờ sẽ vượt", "bao gio se vuot",
			"which day", "what day", "daily", "by day", "day by day", "spike", "yesterday", "sunday", "saturday"
	);

	// en: "when" chỉ tính khi đi kèm exceed / over ("when will it exceed", "when over Fluct")
	private static final Pattern WHEN_EXCEED = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])when(?![\\p{L}\\p{N}_]).*(?<![\\p{L}\\p{N}_])(?:exceed\\w*|over)(?![\\p{L}\\p{N}_])"
	);

	// Prefetch nhiều khung: tối đa (PRESS / MOLD / GUIDE × T&SL / RM)
	private static final int MAX_FRAMES = 6;

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
			"vật tư", "vat tu", "cost center", "máy", "category", "item", "material", "machine",
			// "may" không dấu (audit 2026-11-10: "may nao chi nhieu nhat" bị coi là câu hỏi theo ngày)
			"may nao", "top may", "cac may", "may chi", "may ton", "may lon"
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

		// 1 khung: chuỗi đến hết tháng (ngày chưa tới có Estimate (MTD))
		if (frame != null) {
			return "OT".equals(frame.cost()) ? List.of() : List.of(call(1, frame, true));
		}

		// Nhiều khung nêu rõ trong câu hỏi (≥ 2): chuỗi đến ngày D + dòng tóm tắt Estimate, không kéo đến hết tháng
		String q = CostMonitoringQuestion.normalize(question);
		List<String> divs = CostMonitoringQuestion.divs(q);
		List<String> costs = CostMonitoringQuestion.costs(q).stream().filter(c -> !"OT".equals(c)).toList();

		if (divs.isEmpty() || costs.isEmpty() || divs.size() * costs.size() < 2) {
			return List.of();
		}

		List<ToolCall> result = new ArrayList<>();

		for (String div : divs) {
			for (String cost : costs) {
				if (result.size() < MAX_FRAMES) {
					result.add(call(result.size() + 1, new CostMonitoringFocus(div, cost), false));
				}
			}
		}

		return result;
	}

	private static ToolCall call(int index, CostMonitoringFocus frame, boolean monthEnd) {
		return new ToolCall(
				"prefetch-" + TOOL + "-" + index,
				TOOL,
				"{\"div\":\"" + frame.div() + "\",\"cost\":\"" + frame.cost() + "\",\""
						+ DailySeriesTool.ARG_MONTH_END + "\":" + monthEnd + "}"
		);
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

		if (CostMonitoringQuestion.containsAny(q, DAILY_KEYWORDS) || SPECIFIC_DAY.matcher(q).find()
				|| WHEN_EXCEED.matcher(q).find()) {
			return true;
		}

		return MOST_SPENT.matcher(q).find() && !CostMonitoringQuestion.containsAny(q, ITEM_KEYWORDS);
	}
}
