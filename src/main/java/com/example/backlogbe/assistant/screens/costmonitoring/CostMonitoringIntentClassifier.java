package com.example.backlogbe.assistant.screens.costmonitoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// =========================================================
// PHÂN LOẠI INTENT CÂU HỎI COST MONITORING
//
// fold: chữ thường + bỏ dấu tiếng Việt (đ -> d) + gộp khoảng trắng ("bô phận" = "bộ phận" = "bo phan").
// Mọi từ khóa viết KHÔNG dấu, khớp nguyên từ trên câu đã fold.
//
// Tín hiệu (mỗi tín hiệu ghi lại từ khóa đã khớp – audit intentMatch):
//   WHY      : vi sao, tai sao, nguyen nhan, ly do, do dau, why, cause, reason
//   COMPARE  : ≥ 2 bộ phận, hoặc 1 bộ phận + ≥ 2 loại chi phí (≥ 2 khung); hoặc so sanh, vs, versus, voi, chart nao,
//              ben nao, tot hon, te hon, compare, better, worse. Bỏ qua khi nhắc tháng trước / cùng kỳ
//              (compare_previous_month lo).
//   RANKING  : "nhat" (trừ "cap nhat", "chu nhat"), xep hang, rank, most, highest, worst. Bỏ qua khi hỏi vật tư / máy /
//              cost center / Category hoặc theo ngày (không phải xếp hạng khung).
//   FORECAST : cuoi thang, du bao, cu chi, giu toc do, se vuot, "vuot … khong / ko / chua", forecast…
//   OVERVIEW : bat thuong, can theo doi, can chu y, dang lo, nguy co, rui ro, risk, abnormal…; tinh hinh, tong quan,
//              overview (chỉ khi không hỏi vật tư / máy / Ratio / Fluct / theo ngày) – khi không nêu đúng 1 bộ phận, không hỏi về ô
//              đang xem (drawer, không nêu bộ phận), không nhắc tháng trước.
// Intent chính theo thứ tự ưu tiên: WHY > COMPARE > RANKING > FORECAST > OVERVIEW. Không khớp gì -> null
// (KHÔNG mặc định overview).
//
// certain() – được phép trả lời bằng template:
//   có intent chính, không có từ khóa không chắc chắn (danh gia, so sanh, tot, nen, tai sao, vi sao),
//   và chỉ khớp 1 intent cụ thể trong WHY / COMPARE / RANKING / FORECAST.
//   OVERVIEW không tính là "khớp thêm": "bất thường nhất" = xếp hạng theo mức bất thường, "có gì bất thường, cuối
//   tháng có vượt không" = dự báo chung (cùng các nhóm khung).
// =========================================================
final class CostMonitoringIntentClassifier {

	private CostMonitoringIntentClassifier() {
	}

	enum Signal {
		WHY, COMPARE, RANKING, FORECAST, OVERVIEW
	}

	private static final List<Signal> PRIORITY =
			List.of(Signal.WHY, Signal.COMPARE, Signal.RANKING, Signal.FORECAST, Signal.OVERVIEW);

	private static final List<String> WHY = List.of(
			"vi sao", "tai sao", "nguyen nhan", "ly do", "do dau", "why", "cause", "reason"
	);

	private static final List<String> COMPARE = List.of(
			"so sanh", "vs", "versus", "voi", "chart nao", "ben nao", "tot hon", "te hon",
			"compare", "better", "worse"
	);

	private static final List<String> RANKING = List.of(
			"nhat", "xep hang", "rank", "ranking", "most", "highest", "worst"
	);

	private static final List<String> FORECAST = List.of(
			"cu chi", "giu toc do", "toc do hien tai", "toc do nay", "cuoi thang", "du bao", "se vuot",
			"forecast", "projection", "projected", "end of month", "month-end", "month end",
			"at this rate", "current rate", "keep spending", "will it exceed", "going to exceed"
	);

	// "(có) vượt (FC / Fluct / …) không / ko / chưa"
	private static final Pattern WILL_EXCEED = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])vuot(?:\\s+[^\\s?]+){0,4}?\\s+(?:khong|ko|k|chua|hong)(?![\\p{L}\\p{N}_])"
	);

	private static final List<String> OVERVIEW = List.of(
			"bat thuong", "can theo doi", "can chu y", "dang lo", "nguy co", "rui ro",
			"at risk", "risk", "abnormal", "unusual", "anomal", "watch out", "need attention", "needs attention", "worry"
	);

	// Có các từ này -> không dùng template (đi LLM)
	private static final List<String> UNCERTAIN = List.of(
			"danh gia", "so sanh", "tot", "nen", "tai sao", "vi sao"
	);

	private static final List<String> CURRENT_RATE = List.of(
			"cu chi", "giu toc do", "toc do hien tai", "toc do nay", "chi nhu vay", "chi nhu nay",
			"at this rate", "current rate", "keep spending"
	);

	private static final List<String> PREVIOUS_MONTH = List.of(
			"thang truoc", "cung ky", "previous month", "last month"
	);

	// Hỏi vật tư / máy / cost center / Category: không phải xếp hạng khung
	private static final List<String> DETAIL = List.of(
			"vat tu", "cost center", "category", "may", "item", "items", "machine", "material"
	);

	// Không khớp "cap nhat" (cập nhật), "chu nhat" (Chủ nhật – audit 2026-11-10 bị xếp hạng nhầm)
	private static final Pattern NHAT = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])(?<!cap )(?<!chu )nhat(?![\\p{L}\\p{N}_])");

	// Hỏi tình hình chung (không nêu khung): chỉ tính khi không hỏi chi tiết vật tư / máy / Ratio / Fluct / theo ngày
	private static final List<String> OVERVIEW_GENERAL = List.of(
			"tinh hinh", "tong quan", "overview", "situation", "how is spending", "how are costs"
	);

	private static final List<String> OVERVIEW_GENERAL_EXCLUDE = List.of(
			"vat tu", "cost center", "category", "may nao", "item", "items", "machine", "material", "ratio", "fluct"
	);

	/*
	 * primary   : intent chính theo thứ tự ưu tiên; null = không khớp intent nào
	 * matched   : tín hiệu -> từ khóa đã khớp (thứ tự ưu tiên)
	 * uncertain : từ khóa không chắc chắn đã khớp
	 * ignored   : tín hiệu bị bỏ và lý do (vd "ranking: nhat (vật tư / theo ngày)")
	 * divs/costs: bộ phận / loại chi phí nêu trong câu hỏi
	 */
	record Result(
			Signal primary,
			Map<Signal, List<String>> matched,
			List<String> uncertain,
			List<String> ignored,
			List<String> divs,
			List<String> costs,
			boolean currentRate,
			boolean previousMonth
	) {

		boolean has(Signal s) {
			return matched.containsKey(s);
		}

		// Số intent cụ thể đã khớp (OVERVIEW không tính)
		int specificCount() {
			return (int) matched.keySet().stream().filter(s -> s != Signal.OVERVIEW).count();
		}

		boolean certain() {
			return primary != null && uncertain.isEmpty() && specificCount() <= 1;
		}

		// Chuỗi debug cho audit: "primary=ranking; matched=ranking(nhat), overview(bat thuong); uncertain=-; template=yes"
		String describe() {

			StringJoiner m = new StringJoiner(", ");
			matched.forEach((s, kws) -> m.add(name(s) + "(" + String.join("|", kws) + ")"));

			return "primary=" + (primary == null ? "none" : name(primary))
					+ "; matched=" + (matched.isEmpty() ? "-" : m.toString())
					+ "; uncertain=" + (uncertain.isEmpty() ? "-" : String.join("|", uncertain))
					+ (ignored.isEmpty() ? "" : "; ignored=" + String.join(", ", ignored))
					+ "; template=" + (certain() ? "yes" : "no");
		}

		private static String name(Signal s) {
			return s.name().toLowerCase(Locale.ROOT);
		}
	}

	// Chữ thường, bỏ dấu tiếng Việt (đ -> d), gộp khoảng trắng
	static String fold(String text) {

		if (text == null) {
			return "";
		}

		String s = Normalizer.normalize(text, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.replace('đ', 'd').replace('Đ', 'D')
				.toLowerCase(Locale.ROOT);

		return s.replaceAll("\\s+", " ").trim();
	}

	/**
	 * @param question câu hỏi gốc
	 * @param focus    ô đang xem (drawer); null = không có
	 */
	static Result classify(String question, CostMonitoringFocus focus) {

		String f = fold(question);

		List<String> divs = CostMonitoringQuestion.divs(f);
		List<String> costs = CostMonitoringQuestion.costs(f);
		boolean previousMonth = !found(f, PREVIOUS_MONTH).isEmpty();

		Map<Signal, List<String>> matched = new EnumMap<>(Signal.class);
		List<String> ignored = new ArrayList<>();

		put(matched, Signal.WHY, found(f, WHY));

		// COMPARE
		List<String> compare = new ArrayList<>(found(f, COMPARE));
		if (divs.size() >= 2 || (divs.size() == 1 && costs.size() >= 2)) {
			compare.add(">=2 khung");
		}
		if (!compare.isEmpty() && previousMonth) {
			ignored.add("compare: " + String.join("|", compare) + " (tháng trước)");
		} else {
			put(matched, Signal.COMPARE, compare);
		}

		// RANKING
		List<String> ranking = new ArrayList<>();
		if (NHAT.matcher(f).find()) {
			ranking.add("nhat");
		}
		for (String kw : found(f, RANKING)) {
			if (!"nhat".equals(kw)) {
				ranking.add(kw);
			}
		}
		if (!ranking.isEmpty()
				&& (!found(f, DETAIL).isEmpty() || DailySeriesPrefetchRule.isDailyQuestion(question))) {
			ignored.add("ranking: " + String.join("|", ranking) + " (vật tư / theo ngày)");
		} else {
			put(matched, Signal.RANKING, ranking);
		}

		// FORECAST
		List<String> forecast = new ArrayList<>(found(f, FORECAST));
		Matcher willExceed = WILL_EXCEED.matcher(f);
		if (willExceed.find()) {
			forecast.add(willExceed.group());
		}
		put(matched, Signal.FORECAST, forecast);

		// OVERVIEW: không nêu đúng 1 bộ phận, không hỏi về ô đang xem, không so với tháng trước
		List<String> overview = new ArrayList<>(found(f, OVERVIEW));
		if (found(f, OVERVIEW_GENERAL_EXCLUDE).isEmpty() && !DailySeriesPrefetchRule.isDailyQuestion(question)) {
			overview.addAll(found(f, OVERVIEW_GENERAL));
		}
		if (!overview.isEmpty()) {
			if (previousMonth || divs.size() == 1 || (divs.isEmpty() && focus != null)) {
				ignored.add("overview: " + String.join("|", overview) + " (1 bộ phận / ô đang xem / tháng trước)");
			} else {
				put(matched, Signal.OVERVIEW, overview);
			}
		}

		Signal primary = PRIORITY.stream().filter(matched::containsKey).findFirst().orElse(null);

		return new Result(primary, matched, found(f, UNCERTAIN), ignored, divs, costs,
				!found(f, CURRENT_RATE).isEmpty(), previousMonth);
	}

	private static void put(Map<Signal, List<String>> matched, Signal signal, List<String> keywords) {
		if (!keywords.isEmpty()) {
			matched.put(signal, List.copyOf(keywords));
		}
	}

	// Từ khóa (không dấu) khớp nguyên từ trong câu đã fold
	private static List<String> found(String folded, List<String> keywords) {

		List<String> result = new ArrayList<>();

		for (String kw : keywords) {
			if (CostMonitoringQuestion.word(Pattern.quote(kw)).matcher(folded).find()) {
				result.add(kw);
			}
		}

		return result;
	}
}
