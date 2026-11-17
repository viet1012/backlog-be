package com.example.backlogbe.assistant.core.month;

import com.example.backlogbe.assistant.core.prompt.PromptBuilder;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// =========================================================
// DÒ THÁNG TRONG CÂU HỎI
//
// Tháng đang xem: param "month" (yyyy-MM / yyyy.MM); không có param mà màn hình hỗ trợ tháng
//   (ScreenModule.supportsMonthQuestions) -> tháng hiện tại; còn lại -> không dò.
// Dạng nhận (không phân biệt hoa thường, có dấu / không dấu):
//   "tháng 10", "thang 10", "tháng 10/2026", "tháng 10 năm 2026"
//   "T10", "T10/2026"   (T2..T7 không kèm năm bị bỏ qua vì trùng thứ trong tuần: T2 = thứ Hai…)
//   "10/2026", "5/10/2026" (lấy 10/2026)
//   "2026.10", "2026-10", "2026/10"
//   "october", "october 2026" (tên đầy đủ tiếng Anh; "may" chỉ nhận khi kèm năm vì trùng "máy" không dấu)
// Không kèm năm -> năm của tháng đang xem.
// plan(): tháng khác tháng đang xem, trong 24 tháng gần nhất và không vượt tháng hiện tại, tối đa 2
//   -> ChatOrchestrator dựng thêm context cho các tháng đó (màn hình hỗ trợ tháng) + answerMonthNote.
// Không dựng được (ngoài phạm vi / màn khác) mà câu hỏi chỉ nhắc tháng khác -> note() nhắc đổi tháng.
//
// KHOẢNG NHIỀU THÁNG (range(), MonthRange; 2..MAX_RANGE_MONTHS tháng, cắt tại tháng hiện tại):
//   "trong 3 tháng", "3 tháng gần nhất / qua / gần đây", "last / past 3 months"  -> 3 tháng tính cả tháng đang xem
//   "3 tháng trước", "previous / prior 3 months"                               -> 3 tháng đủ trước tháng đang xem
//   "các / mấy / những tháng gần đây", "recent months", "nửa năm"              -> 3 / 6 tháng tính cả tháng đang xem
//   "nửa năm đầu / cuối"                                                      -> tháng 1–6 / 7–12 của năm đang xem
//   "từ tháng 8 đến tháng 10", "tháng 8 - 10", "tháng 8 – tháng 10/2026"        -> đúng khoảng đó
//   "từ tháng 8 (đến nay)"                                                    -> tháng 8 tới tháng đang xem
//   "2026-09 đến 2026-11", "from 2026-09 to 2026-11"                          -> đúng khoảng đó
//   "quý này / quý trước / quý 3 (năm 2026)", "this / last quarter", "Q3"       -> quý (quý này: tới tháng đang xem)
//   "từ đầu năm", "năm nay", "YTD", "this year"                               -> tháng 1 tới tháng đang xem
//   "3 tháng tới / next 3 months" (tương lai), "ngày 3 tháng 10"              -> không phải khoảng
// Có khoảng: mentioned = các tháng trong khoảng, extra lấy từ tháng gần nhất trở về trước (tối đa MAX_EXTRA_MONTHS);
//   ChatOrchestrator gọi ScreenModule.answerGuideForRange (template theo khoảng) rồi mới tới LLM + rangeNote.
// =========================================================
public final class QuestionMonthDetector {

	private QuestionMonthDetector() {
	}

	private static final String MONTH = "(1[0-2]|0?[1-9])(?!\\d)";
	private static final String YEAR = "(20\\d{2})(?!\\d)";

	// tháng 10 | thang 10 [/ - năm 2026]
	private static final Pattern VN = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:tháng|thang)\\s*" + MONTH
					+ "(?:\\s*(?:/|-|năm|nam)\\s*" + YEAR + ")?");

	// T10 [/2026]
	private static final Pattern T_FORM = Pattern.compile(
			"(?<![\\p{L}\\p{N}&_])t" + MONTH + "(?:\\s*[/.\\-]\\s*" + YEAR + ")?");

	// 10/2026
	private static final Pattern MONTH_YEAR = Pattern.compile("(?<!\\d)" + MONTH + "\\s*/\\s*" + YEAR);

	// 2026.10 | 2026-10 | 2026/10
	private static final Pattern YEAR_MONTH = Pattern.compile("(?<!\\d)" + YEAR + "\\s*[.\\-/]\\s*" + MONTH);

	private static final Map<String, Integer> EN_MONTHS = new TreeMap<>(Map.ofEntries(
			Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3),
			Map.entry("april", 4), Map.entry("may", 5), Map.entry("june", 6),
			Map.entry("july", 7), Map.entry("august", 8), Map.entry("september", 9),
			Map.entry("october", 10), Map.entry("november", 11), Map.entry("december", 12)
	));

	private static final Pattern EN = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(" + String.join("|", EN_MONTHS.keySet()) + ")(?![\\p{L}\\p{N}])"
					+ "(?:\\s*,?\\s*" + YEAR + ")?");

	// Số tháng khác tối đa được dựng thêm context
	static final int MAX_EXTRA_MONTHS = 2;

	// Chỉ dựng context cho tháng trong 24 tháng gần nhất (tháng hiện tại và 23 tháng trước), không vượt tháng hiện tại
	static final int MAX_MONTHS_BACK = 23;

	/*
	 * viewed          : tháng đang xem
	 * mentioned       : các tháng nhắc trong câu hỏi (thứ tự xuất hiện, không trùng)
	 * viewedMentioned : câu hỏi có nhắc cả tháng đang xem (vd so sánh)
	 * extra           : tháng khác tháng đang xem, trong phạm vi cho phép, tối đa MAX_EXTRA_MONTHS
	 * outOfRange      : tháng khác tháng đang xem nhưng ngoài phạm vi (tương lai / quá 24 tháng)
	 */
	public record MonthPlan(
			YearMonth viewed,
			List<YearMonth> mentioned,
			boolean viewedMentioned,
			List<YearMonth> extra,
			List<YearMonth> outOfRange,
			MonthRange range
	) {
		// Có nhắc tháng khác mà không nhắc tháng đang xem
		public boolean mismatch() {
			return !mentioned.isEmpty() && !viewedMentioned;
		}
	}

	// Khoảng tháng liên tục [from, to] (from ≤ to)
	public record MonthRange(YearMonth from, YearMonth to) {

		public int months() {
			return (int) from.until(to, java.time.temporal.ChronoUnit.MONTHS) + 1;
		}

		public boolean contains(YearMonth ym) {
			return ym != null && !ym.isBefore(from) && !ym.isAfter(to);
		}

		// Các tháng theo thứ tự thời gian
		public List<YearMonth> list() {
			List<YearMonth> result = new ArrayList<>();
			for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
				result.add(ym);
			}
			return result;
		}

		// "2026-09 → 2026-11"
		public String label() {
			return from + " → " + to;
		}
	}

	// Khoảng tối đa nhận (dài hơn -> không coi là khoảng)
	public static final int MAX_RANGE_MONTHS = 12;

	// Không dò được tháng đang xem -> null
	// supportsMonth: màn hình có khái niệm tháng (ScreenModule.supportsMonthQuestions)
	public static MonthPlan plan(String question, boolean supportsMonth, Map<String, String> params, YearMonth current) {

		YearMonth viewed = viewedMonth(supportsMonth, params);

		if (viewed == null || question == null) {
			return null;
		}

		// Khoảng nhiều tháng: thay cho các tháng nêu lẻ (đầu / cuối khoảng đã nằm trong khoảng)
		MonthRange range = range(question, viewed, current);
		List<YearMonth> mentioned = range != null ? range.list() : detect(question, viewed.getYear());
		List<YearMonth> extra = new ArrayList<>();
		List<YearMonth> outOfRange = new ArrayList<>();

		YearMonth oldest = current.minusMonths(MAX_MONTHS_BACK);

		// Khoảng: dựng context từ tháng gần nhất trở về trước (giới hạn MAX_EXTRA_MONTHS -> giữ các tháng sát nhất)
		List<YearMonth> order = new ArrayList<>(mentioned);
		if (range != null) {
			java.util.Collections.reverse(order);
		}

		for (YearMonth ym : order) {

			if (ym.equals(viewed)) {
				continue;
			}

			if (ym.isAfter(current) || ym.isBefore(oldest)) {
				outOfRange.add(ym);
			} else if (extra.size() < MAX_EXTRA_MONTHS) {
				extra.add(ym);
			}
		}

		return new MonthPlan(viewed, mentioned, mentioned.contains(viewed), extra, outOfRange, range);
	}

	// =========================================================
	// KHOẢNG NHIỀU THÁNG
	// =========================================================

	private static final String NUM = "(\\d{1,2}|hai|ba|bon|two|three|four|five|six|twelve)";
	private static final Map<String, Integer> NUM_WORDS = Map.of(
			"hai", 2, "ba", 3, "bon", 4, "two", 2, "three", 3, "four", 4, "five", 5, "six", 6, "twelve", 12);

	// "trong 3 thang", "3 thang gan nhat", "3 thang truoc", "last 3 months", "previous 3 months"
	// (?!\s*\d): "3 thang 10" là ngày; tương lai ("3 thang toi", "next 3 months") bị loại riêng
	private static final Pattern REL_RANGE = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:(next|previous|prior|last|past)\\s+)?" + NUM + "\\s*(?:thang|months?)(?![\\p{L}\\p{N}])(?!\\s*[/.\\-]?\\s*\\d)"
					+ "(?:\\s+(truoc|toi|sap toi|tiep theo|ke tiep|sau))?");

	private static final Pattern RECENT = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:(?:cac|may|nhung|vai) thang (?:gan day|gan nhat|qua|vua qua)|recent months|last few months|past few months)(?![\\p{L}\\p{N}])");
	private static final Pattern HALF_YEAR = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:nua nam|half (?:a )?year)(?:\\s+(truoc|toi|sau|dau|cuoi))?(?![\\p{L}\\p{N}])");

	// "tu thang 8 den thang 10", "thang 8 - 10", "thang 8/2026 den thang 2/2027"
	private static final Pattern EXPLICIT_RANGE = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:tu\\s+)?thang\\s*(1[0-2]|0?[1-9])(?:\\s*[/\\-]\\s*(20\\d{2})|\\s+nam\\s+(20\\d{2}))?"
					+ "\\s*(?:den|toi|->|→|~|–|-)\\s*(?:het\\s+)?(?:thang\\s*)?(1[0-2]|0?[1-9])(?!\\d)"
					+ "(?:\\s*[/\\-]\\s*(20\\d{2})|\\s+nam\\s+(20\\d{2}))?");

	// "2026-09 den 2026-11", "from 2026-09 to 2026-11" (câu gợi ý theo khoảng dùng dạng này)
	private static final Pattern YM_RANGE = Pattern.compile(
			"(?<!\\d)(20\\d{2})[-./](1[0-2]|0?[1-9])(?!\\d)\\s*(?:den|toi|to|until|through|->|→|~|–|-)\\s*"
					+ "(20\\d{2})[-./](1[0-2]|0?[1-9])(?!\\d)");

	private static final Pattern QUARTER_THIS =Pattern.compile("(?<![\\p{L}\\p{N}])(?:quy nay|this quarter)(?![\\p{L}\\p{N}])");
	private static final Pattern QUARTER_LAST = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:quy truoc|last quarter|previous quarter)(?![\\p{L}\\p{N}])");
	private static final Pattern QUARTER_N = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:quy\\s*([1-4])|q([1-4]))(?:\\s*(?:/|-|nam)\\s*(20\\d{2}))?(?![\\p{L}\\p{N}])");
	// "thang 3 nam nay" là 1 tháng, không phải từ đầu năm
	private static final Pattern YTD = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?<!thang \\d{1,2} )(?:tu dau nam|dau nam den nay|nam nay|ytd|year to date|this year)(?![\\p{L}\\p{N}])");
	// "tu thang 8 (den nay)", "since august" không hỗ trợ tên tháng; mốc cuối = tháng đang xem
	private static final Pattern SINCE = Pattern.compile(
			"(?<![\\p{L}\\p{N}])tu\\s+thang\\s*(1[0-2]|0?[1-9])(?!\\d)(?:\\s*[/\\-]\\s*(20\\d{2})|\\s+nam\\s+(20\\d{2}))?"
					+ "(?!\\s*(?:den|toi|->|→|~|–|-)\\s*(?:het\\s+)?(?:thang\\s*)?\\d)");

	/**
	 * Khoảng nhiều tháng trong câu hỏi; null = không có (hoặc < 2 tháng / > MAX_RANGE_MONTHS / hoàn toàn tương lai).
	 *
	 * @param viewed  tháng đang xem (mốc cho khoảng tương đối)
	 * @param current tháng hiện tại (cắt phần tương lai)
	 */
	public static MonthRange range(String question, YearMonth viewed, YearMonth current) {

		if (question == null || viewed == null) {
			return null;
		}

		String f = fold(question);
		YearMonth from = null;
		YearMonth to = null;

		Matcher m = EXPLICIT_RANGE.matcher(f);
		Matcher rel = REL_RANGE.matcher(f);
		Matcher q = QUARTER_N.matcher(f);
		Matcher half = HALF_YEAR.matcher(f);
		Matcher since = SINCE.matcher(f);
		Matcher ym = YM_RANGE.matcher(f);

		if (ym.find()) {
			from = YearMonth.of(Integer.parseInt(ym.group(1)), Integer.parseInt(ym.group(2)));
			to = YearMonth.of(Integer.parseInt(ym.group(3)), Integer.parseInt(ym.group(4)));
		} else if (m.find()) {
			int m1 = Integer.parseInt(m.group(1));
			int m2 = Integer.parseInt(m.group(4));
			String y1 = m.group(2) != null ? m.group(2) : m.group(3);
			String y2 = m.group(5) != null ? m.group(5) : m.group(6);
			int year2 = y2 != null ? Integer.parseInt(y2) : y1 != null ? Integer.parseInt(y1) + (m2 < m1 ? 1 : 0) : viewed.getYear();
			int year1 = y1 != null ? Integer.parseInt(y1) : m1 > m2 ? year2 - 1 : year2;
			from = YearMonth.of(year1, m1);
			to = YearMonth.of(year2, m2);
		} else if (since.find()) {
			int m1 = Integer.parseInt(since.group(1));
			String y1 = since.group(2) != null ? since.group(2) : since.group(3);
			to = viewed;
			from = YearMonth.of(y1 != null ? Integer.parseInt(y1) : m1 > viewed.getMonthValue() ? viewed.getYear() - 1 : viewed.getYear(), m1);
		} else if (rel.find()) {
			String pre = rel.group(1);
			String post = rel.group(3);
			if ("next".equals(pre) || (post != null && !"truoc".equals(post))) {
				return null;
			}
			String num = rel.group(2);
			int n = Character.isDigit(num.charAt(0)) ? Integer.parseInt(num) : NUM_WORDS.getOrDefault(num, 0);
			if (n < 2) {
				return null;
			}
			boolean before = "previous".equals(pre) || "prior".equals(pre) || "truoc".equals(post);
			to = before ? viewed.minusMonths(1) : viewed;
			from = to.minusMonths(n - 1L);
		} else if (RECENT.matcher(f).find()) {
			to = viewed;
			from = viewed.minusMonths(2);
		} else if (half.find()) {
			String post = half.group(1);
			if ("dau".equals(post) || "cuoi".equals(post)) {
				// nửa năm đầu / cuối của năm đang xem
				from = YearMonth.of(viewed.getYear(), "dau".equals(post) ? 1 : 7);
				to = from.plusMonths(5);
			} else if (post != null && !"truoc".equals(post)) {
				return null;
			} else {
				to = post != null ? viewed.minusMonths(1) : viewed;
				from = to.minusMonths(5);
			}
		} else if (QUARTER_THIS.matcher(f).find()) {
			from = quarterStart(viewed);
			to = viewed;
		} else if (QUARTER_LAST.matcher(f).find()) {
			from = quarterStart(viewed).minusMonths(3);
			to = from.plusMonths(2);
		} else if (q.find()) {
			int quarter = Integer.parseInt(q.group(1) != null ? q.group(1) : q.group(2));
			int year = q.group(3) != null ? Integer.parseInt(q.group(3)) : viewed.getYear();
			from = YearMonth.of(year, (quarter - 1) * 3 + 1);
			if (q.group(3) == null && from.isAfter(current)) {
				from = from.minusYears(1);
			}
			to = from.plusMonths(2);
		} else if (YTD.matcher(f).find()) {
			from = YearMonth.of(viewed.getYear(), 1);
			to = viewed;
		}

		if (from == null) {
			return null;
		}

		if (to.isAfter(current)) {
			to = current;
		}

		if (to.isBefore(from)) {
			return null;
		}

		MonthRange range = new MonthRange(from, to);
		int months = range.months();

		return months < 2 || months > MAX_RANGE_MONTHS ? null : range;
	}

	private static YearMonth quarterStart(YearMonth ym) {
		return YearMonth.of(ym.getYear(), (ym.getMonthValue() - 1) / 3 * 3 + 1);
	}

	// Bỏ dấu, chữ thường, gộp khoảng trắng
	private static String fold(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.replace('đ', 'd').replace('Đ', 'D')
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ")
				.trim();
	}

	// Câu hỏi về khoảng nhiều tháng (đi kèm câu hỏi gửi LLM)
	public static String rangeNote(MonthRange range, YearMonth viewed, List<YearMonth> built, List<YearMonth> noData, String lang) {

		List<String> have = new ArrayList<>();
		if (range.contains(viewed)) {
			have.add(viewed.toString());
		}
		built.stream().filter(range::contains).map(YearMonth::toString).forEach(have::add);
		List<String> missing = range.list().stream()
				.filter(ym -> !have.contains(ym.toString()) && !noData.contains(ym))
				.map(YearMonth::toString).toList();

		if (PromptBuilder.LANG_EN.equals(lang)) {
			return "(System note: the question is about the range " + range.label() + " (" + range.months() + " months). "
					+ "Attached data: " + (have.isEmpty() ? "none" : String.join(", ", have)) + ". "
					+ "Answer month by month across the range, naming each month; do not answer with the month on screen only"
					+ (missing.isEmpty() ? "" : "; months " + String.join(", ", missing)
					+ " are not attached – say they were not reviewed, do not guess") + ".)";
		}

		return "(Lưu ý hệ thống: câu hỏi về khoảng " + range.label() + " (" + range.months() + " tháng). "
				+ "Dữ liệu gửi kèm: " + (have.isEmpty() ? "không có" : String.join(", ", have)) + ". "
				+ "Trả lời theo từng tháng trong khoảng, ghi rõ tháng; không trả lời riêng tháng đang xem"
				+ (missing.isEmpty() ? "" : "; các tháng " + String.join(", ", missing)
				+ " không có dữ liệu gửi kèm – nói rõ là chưa xem được, không tự suy") + ".)";
	}

	// Đã dựng context cho các tháng built: trả lời theo dữ liệu tháng đó
	public static String answerMonthNote(List<YearMonth> built, YearMonth viewed, boolean viewedMentioned, String lang) {

		String months = String.join(", ", built.stream().map(YearMonth::toString).toList());

		if (PromptBuilder.LANG_EN.equals(lang)) {
			return "(System note: answer using the attached data for month " + months
					+ (viewedMentioned ? " and the month on screen " + viewed : "")
					+ "; the first sentence must state which month the answer is for.)";
		}

		return "(Lưu ý hệ thống: trả lời theo dữ liệu tháng " + months
				+ (viewedMentioned ? " và tháng đang xem " + viewed : "")
				+ " đã gửi kèm; câu đầu ghi rõ đang trả lời cho tháng nào.)";
	}

	// Tháng được hỏi chưa có dữ liệu (provider trả null cho context rút gọn)
	public static String noDataNote(YearMonth month, String lang) {

		if (PromptBuilder.LANG_EN.equals(lang)) {
			return "(System note: month " + month + " has no data in the system yet; state this clearly.)";
		}

		return "(Lưu ý hệ thống: tháng " + month + " chưa có dữ liệu trên hệ thống; nói rõ điều này.)";
	}

	// Không dựng được context tháng được hỏi (ngoài phạm vi / màn khác / lỗi): nhắc đổi tháng trên màn hình
	public static String note(YearMonth asked, YearMonth viewed, String lang) {

		if (PromptBuilder.LANG_EN.equals(lang)) {
			return "(System note: the question mentions month " + asked + " but the screen is showing month "
					+ viewed + "; state this in the first sentence and remind the user to change the month on the screen.)";
		}

		return "(Lưu ý hệ thống: câu hỏi nhắc tháng " + asked + " nhưng màn hình đang xem tháng " + viewed
				+ "; nói rõ điều này ở câu đầu và nhắc người dùng đổi tháng trên màn hình.)";
	}

	// param "month"; không có -> tháng hiện tại nếu màn hình hỗ trợ tháng, không thì null
	public static YearMonth viewedMonth(boolean supportsMonth, Map<String, String> params) {

		String month = params == null ? null : params.get("month");

		if (month == null || month.isBlank()) {
			return supportsMonth ? YearMonth.now() : null;
		}

		try {
			return YearMonth.parse(month.trim().replace('.', '-'));
		} catch (DateTimeException e) {
			return null;
		}
	}

	// Các tháng nhắc trong câu hỏi, theo thứ tự xuất hiện, bỏ trùng
	public static List<YearMonth> detect(String question, int defaultYear) {

		String q = Normalizer.normalize(question, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

		TreeMap<Integer, YearMonth> byPosition = new TreeMap<>();

		collect(byPosition, VN.matcher(q), 1, 2, defaultYear, false);
		collect(byPosition, T_FORM.matcher(q), 1, 2, defaultYear, true);
		collect(byPosition, MONTH_YEAR.matcher(q), 1, 2, defaultYear, false);
		collect(byPosition, YEAR_MONTH.matcher(q), 2, 1, defaultYear, false);

		Matcher en = EN.matcher(q);
		while (en.find()) {
			String name = en.group(1);
			String year = en.group(2);
			if ("may".equals(name) && year == null) {
				continue;
			}
			put(byPosition, en.start(), EN_MONTHS.get(name), year, defaultYear);
		}

		List<YearMonth> result = new ArrayList<>();
		for (YearMonth ym : byPosition.values()) {
			if (!result.contains(ym)) {
				result.add(ym);
			}
		}

		return result;
	}

	private static void collect(
			TreeMap<Integer, YearMonth> out,
			Matcher m,
			int monthGroup,
			int yearGroup,
			int defaultYear,
			boolean skipWeekdayWithoutYear
	) {

		while (m.find()) {

			int month = Integer.parseInt(m.group(monthGroup));
			String year = m.group(yearGroup);

			// T2..T7 không kèm năm = thứ trong tuần
			if (skipWeekdayWithoutYear && year == null && month >= 2 && month <= 7) {
				continue;
			}

			put(out, m.start(), month, year, defaultYear);
		}
	}

	private static void put(TreeMap<Integer, YearMonth> out, int position, int month, String year, int defaultYear) {
		out.putIfAbsent(position, YearMonth.of(year != null ? Integer.parseInt(year) : defaultYear, month));
	}
}
