package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.api.dto.ToolCall;
import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// =========================================================
// PRE-FETCH get_ratio_trend
//
// Điều kiện: screenKey = cost-monitoring + câu hỏi về xu hướng Fluct (isFluctTrendQuestion):
//   có "fluct" + một cụm vì sao / tại sao / cao hơn / thấp hơn / tăng / giảm… (có dấu / không dấu, vi / en);
//   hoặc "target / tgt" + nâng / hạ; hoặc "ratio" + thay đổi / tăng / giảm / xu hướng.
// div rút từ câu hỏi (từ đứng riêng, không phân biệt hoa thường):
//   PRESS: "press" | MOLD: "mold" | GUIDE: "guide", "pguide", "mguide"
//   Không nhắc bộ phận nào -> chạy cả PRESS, MOLD, GUIDE.
// Không pre-fetch được -> fallbackHint nhắc model tự gọi get_ratio_trend.
// =========================================================
@Component
public class RatioTrendPrefetchRule implements PrefetchRule {

	public static final String TOOL = "get_ratio_trend";

	private static final List<String> ALL_DIVS = List.of("PRESS", "MOLD", "GUIDE");

	private static final Pattern PRESS = word("press");
	private static final Pattern MOLD = word("mold");
	private static final Pattern GUIDE = word("p?guide|mguide");

	// Câu hỏi về Fluct (có "fluct" + một cụm dưới đây)
	private static final String FLUCT_KEYWORD = "fluct";
	private static final List<String> FLUCT_TREND_KEYWORDS = List.of(
			"vì sao", "vi sao", "tại sao", "tai sao", "sao ",
			"cao hơn", "cao hon", "thấp hơn", "thap hon",
			"tăng", "tang", "giảm", "giam",
			"why", "higher", "lower", "increase", "decrease", "rise", "drop", "went up", "went down"
	);

	// Trên câu đã fold (không dấu)
	private static final Pattern EXCEEDS_FLUCT = Pattern.compile(
			"(?:vuot|duoi|trong|exceed|exceeds|above|below|within)\\s+(?:\\S+\\s+)?fluct");
	private static final Pattern TARGET = word("target|tgt");
	private static final Pattern TARGET_MOVE = word("nang|ha|bi ha|duoc nang|raised|lowered|lifted|cut");
	private static final Pattern RATIO = word("ratio");
	private static final Pattern RATIO_MOVE = word(
			"thay doi|bien dong|tang|giam|doi chieu|xu huong|change|changed|trend|increase|decrease");

	private static final String HINT_VI = "(Lưu ý hệ thống: gọi get_ratio_trend trước khi trả lời.)";
	private static final String HINT_EN = "(System note: call get_ratio_trend before answering.)";

	@Override
	public boolean matches(String question, String screenKey) {
		return CostMonitoringModule.SCREEN_KEY.equals(screenKey) && isFluctTrendQuestion(question);
	}

	@Override
	public List<ToolCall> calls(String question, Map<String, String> params) {

		List<ToolCall> calls = new ArrayList<>();
		int i = 0;

		for (String div : divs(question)) {
			calls.add(new ToolCall("prefetch-" + TOOL + "-" + (++i), TOOL, "{\"div\":\"" + div + "\"}"));
		}

		return calls;
	}

	@Override
	public String fallbackHint(String lang) {
		return CmText.isEn(lang) ? HINT_EN : HINT_VI;
	}

	// "vì sao Fluct …", "Fluct cao/thấp hơn FC", "Fluct tăng/giảm" (có dấu hoặc không dấu, vi / en)
	public static boolean isFluctTrendQuestion(String question) {

		if (question == null) {
			return false;
		}

		String q = question.toLowerCase(Locale.ROOT);

		// "Target … nâng / hạ", "Ratio … thay đổi / tăng / giảm" (audit 2026-11-10: không gọi tool, trả lời không có số)
		String folded = CostMonitoringIntentClassifier.fold(question);
		if (TARGET.matcher(folded).find() && TARGET_MOVE.matcher(folded).find()) {
			return true;
		}
		if (RATIO.matcher(folded).find() && RATIO_MOVE.matcher(folded).find()) {
			return true;
		}

		if (!q.contains(FLUCT_KEYWORD)) {
			return false;
		}

		// "vì sao X vượt Fluct", "có vượt Fluct không": hỏi về khung so với mốc Fluct, không phải xu hướng Fluct
		// (test 2026-11-10 ext-10: "Vì sao MOLD RM vượt Fluct?" bị kéo sang get_ratio_trend)
		if (EXCEEDS_FLUCT.matcher(folded).find()) {
			return false;
		}

		for (String keyword : FLUCT_TREND_KEYWORDS) {
			if (q.contains(keyword)) {
				return true;
			}
		}

		return false;
	}

	static List<String> divs(String question) {

		String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
		List<String> divs = new ArrayList<>();

		if (PRESS.matcher(q).find()) {
			divs.add("PRESS");
		}
		if (MOLD.matcher(q).find()) {
			divs.add("MOLD");
		}
		if (GUIDE.matcher(q).find()) {
			divs.add("GUIDE");
		}

		return divs.isEmpty() ? ALL_DIVS : divs;
	}

	private static Pattern word(String regex) {
		return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + regex + ")(?![\\p{L}\\p{N}_])");
	}
}
