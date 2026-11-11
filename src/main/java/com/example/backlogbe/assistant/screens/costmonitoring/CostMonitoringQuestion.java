package com.example.backlogbe.assistant.screens.costmonitoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// =========================================================
// Phân tích câu hỏi Cost Monitoring (dùng chung cho prefetch rule và context provider).
//
// normalize : chữ thường + NFC (tiếng Việt gõ tổ hợp dấu vẫn khớp từ khóa)
// divs      : PRESS ("press", "pr") | MOLD ("mold") | GUIDE ("guide", "pguide", "mguide") – từ đứng riêng
// costs     : OT ("ot") | T&SL ("t&sl", "tsl") | RM ("rm") – từ đứng riêng
// frame     : đúng 1 khung (div × cost) mà câu hỏi nhắm tới:
//             đúng 1 bộ phận + 1 loại chi phí trong câu hỏi; phần thiếu lấy từ ô đang xem (drawer);
//             ≥ 2 bộ phận / loại chi phí, vẫn thiếu, hoặc câu hỏi có ý hỏi khung khác (asksOtherFrames) -> null
//             (= câu hỏi chung: context đầy đủ, không pre-fetch theo khung).
//             Câu nối tiếp "còn … thì sao": chỉ xét phần "…" (+ ô drawer cho phần thiếu), vd drawer PRESS T&SL
//             + "còn RM thì sao?" -> PRESS RM; "…" không nêu bộ phận / loại chi phí nào, hoặc không ghép được
//             đúng 1 khung -> null.
// asksOtherFrames: "khung khác", "khung nào", "các khung", "tất cả (các) khung / bộ phận / loại (chi phí)",
//             en: "other frames / charts / cells", "all (the) frames / charts / cells / divisions / cost types",
//             "which" (trừ "which day" – câu hỏi theo ngày). "tất cả" đi với từ khác (vd "tất cả vật tư") không tính.
// isConcept : câu hỏi khái niệm ("là gì", "cách tính"…) – trả lời từ knowledge, không pre-fetch.
// =========================================================
final class CostMonitoringQuestion {

	private CostMonitoringQuestion() {
	}

	// "pr" = PRESS (viết tắt hay dùng, vd "PR TSL vuot ko"); KHÔNG nhận "mo" cho MOLD ("mở" bỏ dấu cũng là "mo")
	private static final Pattern PRESS = word("press|pr");
	// "mo" chỉ nhận khi đi kèm loại chi phí ("MO T&SL") hoặc đứng cạnh bộ phận khác ("PR với MO") – test 1440 ext-195
	// "PR với MO bên nào chi đậm hơn?" chỉ hiểu PRESS
	private static final Pattern MOLD = word("mold"
			+ "|mo(?=\\s+(?:t\\s*&\\s*sl|tsl|rm|ot)(?![\\p{L}\\p{N}_]))"
			+ "|(?<=(?:pr|press|guide|gu)\\s{1,3}(?:và|va|với|voi|vs|and|,|&)\\s{0,3})mo"
			+ "|mo(?=\\s{0,3}(?:và|va|với|voi|vs|and|,|&)\\s{1,3}(?:pr|press|guide|gu)(?![\\p{L}\\p{N}_]))");
	private static final Pattern GUIDE = word("p?guide|mguide");

	private static final Pattern OT = word("ot");
	private static final Pattern TSL = word("t\\s*&\\s*sl|tsl");
	private static final Pattern RM = word("rm");

	private static final List<String> CONCEPT_KEYWORDS = List.of(
			"là gì", "la gi", "cách tính", "cach tinh", "tính theo cách nào", "tinh theo cach nao",
			"tính như thế nào", "tinh nhu the nao", "nghĩa là", "nghia la", "ý nghĩa", "y nghia",
			"what is", "what does", "how is it calculated", "how do you calculate", "meaning"
	);

	// Hỏi khung khác / nhiều khung
	private static final List<String> OTHER_FRAME_KEYWORDS = List.of(
			"khung khác", "khung khac", "khung nào", "khung nao", "các khung", "cac khung",
			"other frame", "other chart", "other cell"
	);

	// "tất cả (các) khung / bộ phận / loại (chi phí)", "all (the) frames / … / cost types", "which" (trừ "which day")
	private static final Pattern OTHER_FRAME_PATTERN = word(
			"(?:tất cả|tat ca)\\s+(?:(?:các|cac|3)\\s+)?(?:khung|bộ phận|bo phan|loại|loai)"
					+ "|all\\s+(?:the\\s+)?(?:frames|charts|cells|divisions|departments|cost types)"
					+ "|which(?!\\s+days?(?![\\p{L}\\p{N}_]))"
	);

	// Câu nối tiếp "còn … thì sao" (có dấu / không dấu); group 1 = phần "…"
	private static final Pattern FOLLOW_UP = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])(?:còn|con)\\s+(.+?)\\s+(?:thì|thi)\\s+sao(?![\\p{L}\\p{N}_])"
	);

	static String normalize(String question) {
		return question == null
				? ""
				: Normalizer.normalize(question, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
	}

	// q: đã normalize
	static boolean isConcept(String q) {
		return containsAny(q, CONCEPT_KEYWORDS);
	}

	// q: đã normalize
	static boolean asksOtherFrames(String q) {
		return containsAny(q, OTHER_FRAME_KEYWORDS) || OTHER_FRAME_PATTERN.matcher(q).find();
	}

	// q: đã normalize
	static List<String> divs(String q) {

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

		return divs;
	}

	// q: đã normalize
	static List<String> costs(String q) {

		List<String> costs = new ArrayList<>();

		if (OT.matcher(q).find()) {
			costs.add("OT");
		}
		if (TSL.matcher(q).find()) {
			costs.add("T&SL");
		}
		if (RM.matcher(q).find()) {
			costs.add("RM");
		}

		return costs;
	}

	// Khung duy nhất câu hỏi nhắm tới (question chưa normalize); không xác định được -> null
	static CostMonitoringFocus frame(String question, Map<String, String> params) {

		if (question == null || question.isBlank()) {
			return null;
		}

		String q = normalize(question);

		if (asksOtherFrames(q)) {
			return null;
		}

		CostMonitoringFocus focus = CostMonitoringFocus.from(params);

		// "còn … thì sao": chỉ xét phần "…"; phần này không nêu bộ phận / loại chi phí nào -> hỏi khung khác chưa rõ
		Matcher followUp = FOLLOW_UP.matcher(q);
		boolean isFollowUp = followUp.find();
		String scope = isFollowUp ? followUp.group(1) : q;

		List<String> divs = divs(scope);
		List<String> costs = costs(scope);

		if (isFollowUp && divs.isEmpty() && costs.isEmpty()) {
			return null;
		}

		String div = divs.size() == 1 ? divs.get(0) : divs.isEmpty() && focus != null ? focus.div() : null;
		String cost = costs.size() == 1 ? costs.get(0) : costs.isEmpty() && focus != null ? focus.cost() : null;

		return div != null && cost != null ? new CostMonitoringFocus(div, cost) : null;
	}

	static boolean containsAny(String q, List<String> keywords) {

		for (String keyword : keywords) {
			if (q.contains(keyword)) {
				return true;
			}
		}

		return false;
	}

	// Khớp nguyên từ (không dính chữ / số trước sau)
	static Pattern word(String regex) {
		return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + regex + ")(?![\\p{L}\\p{N}_])");
	}
}
