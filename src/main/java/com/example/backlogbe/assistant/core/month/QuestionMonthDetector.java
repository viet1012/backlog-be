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
			List<YearMonth> outOfRange
	) {
		// Có nhắc tháng khác mà không nhắc tháng đang xem
		public boolean mismatch() {
			return !mentioned.isEmpty() && !viewedMentioned;
		}
	}

	// Không dò được tháng đang xem -> null
	// supportsMonth: màn hình có khái niệm tháng (ScreenModule.supportsMonthQuestions)
	public static MonthPlan plan(String question, boolean supportsMonth, Map<String, String> params, YearMonth current) {

		YearMonth viewed = viewedMonth(supportsMonth, params);

		if (viewed == null || question == null) {
			return null;
		}

		List<YearMonth> mentioned = detect(question, viewed.getYear());
		List<YearMonth> extra = new ArrayList<>();
		List<YearMonth> outOfRange = new ArrayList<>();

		YearMonth oldest = current.minusMonths(MAX_MONTHS_BACK);

		for (YearMonth ym : mentioned) {

			if (ym.equals(viewed)) {
				continue;
			}

			if (ym.isAfter(current) || ym.isBefore(oldest)) {
				outOfRange.add(ym);
			} else if (extra.size() < MAX_EXTRA_MONTHS) {
				extra.add(ym);
			}
		}

		return new MonthPlan(viewed, mentioned, mentioned.contains(viewed), extra, outOfRange);
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
