package com.example.backlogbe.assistant.core.month;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

// =========================================================
// THÁNG CHO TOOL (phạm vi 1 câu hỏi)
//
// Tham số tool tùy chọn "month" (yyyy-MM, chấp nhận yyyy.MM):
//   không truyền          -> screenParams như cũ (tháng đang xem / tháng của lượt pre-fetch)
//   = tháng đang xem      -> params màn hình gốc
//   = tháng đã dựng context (tháng được hỏi) -> params theo ScreenModule.paramsForMonth
//   tháng khác / sai dạng -> IllegalArgumentException -> "error=invalid_argument|arg=month|…"
// Màn hình không có tháng (viewed = null): bỏ qua tham số month.
// =========================================================
public final class ToolMonthScope {

	static final String MONTH_ARG = "month";

	private final YearMonth viewed;
	private final Map<String, String> viewedParams;
	private final BiFunction<Map<String, String>, YearMonth, Map<String, String>> paramsForMonth;
	private final Set<YearMonth> allowed = new LinkedHashSet<>();

	/**
	 * @param paramsForMonth params màn hình cho tháng khác tháng đang xem (ScreenModule.paramsForMonth)
	 */
	public ToolMonthScope(
			YearMonth viewed,
			Map<String, String> viewedParams,
			List<YearMonth> extraMonths,
			BiFunction<Map<String, String>, YearMonth, Map<String, String>> paramsForMonth
	) {

		this.viewed = viewed;
		this.viewedParams = viewedParams;
		this.paramsForMonth = paramsForMonth;

		if (viewed != null) {
			allowed.add(viewed);
			allowed.addAll(extraMonths);
		}
	}

	// Params màn hình cho tháng ym (null hoặc = tháng đang xem -> params gốc)
	public Map<String, String> paramsFor(YearMonth ym) {

		if (ym == null || ym.equals(viewed)) {
			return viewedParams;
		}

		return paramsForMonth.apply(viewedParams, ym);
	}

	// Tháng tool thực sự chạy với params này (yyyy-MM): param "month" nếu có, không thì tháng đang xem;
	// màn hình không có tháng / param sai dạng -> null
	public String monthOf(Map<String, String> params) {

		String month = params == null ? null : params.get("month");

		if (month == null || month.isBlank()) {
			return viewed == null ? null : viewed.toString();
		}

		try {
			return YearMonth.parse(month.trim().replace('.', '-')).toString();
		} catch (DateTimeException e) {
			return null;
		}
	}

	// Params dùng để chạy tool theo tham số month của model
	public Map<String, String> resolve(Map<String, Object> args, Map<String, String> screenParams) {

		Object value = args.get(MONTH_ARG);

		if (viewed == null || value == null || value.toString().isBlank()) {
			return screenParams;
		}

		YearMonth ym;
		try {
			ym = YearMonth.parse(value.toString().trim().replace('.', '-'));
		} catch (DateTimeException e) {
			throw new IllegalArgumentException("arg=month|expected=yyyy-MM");
		}

		if (!allowed.contains(ym)) {
			throw new IllegalArgumentException("arg=month|allowed="
					+ String.join(",", allowed.stream().map(YearMonth::toString).toList()));
		}

		return paramsFor(ym);
	}
}
