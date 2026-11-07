package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// =========================================================
// Dữ liệu chart Cost Monitoring của 1 tháng + các giá trị dẫn xuất
// dùng chung cho context provider và tool.
//
// D: ngày (trong tháng) mới nhất có Actual ở bất kỳ khung nào.
//    Chưa có Actual nào -> số ngày đã qua theo lịch (dFromActual = false).
// N: số ngày trong tháng.
// monthClosed(): tháng đang xem < tháng hiện tại, hoặc D = N.
// =========================================================
public record CostMonitoringSnapshot(
		CostMonitoringChartResponse response,
		YearMonth month,
		LocalDate today,
		int d,
		int n,
		boolean dFromActual
) {

	public static final List<String> DIVS = List.of("PRESS", "MOLD", "GUIDE");
	public static final List<String> COSTS = List.of("OT", "T&SL", "RM");

	// Tên bộ phận (dùng với model) <-> mã Div trong SP
	private static final Map<String, String> DIV_CODE = Map.of(
			"PRESS", "PR",
			"MOLD", "MO",
			"GUIDE", "GUIDE"
	);

	private static final Map<String, String> DIV_NAME = Map.of(
			"PR", "PRESS",
			"MO", "MOLD",
			"GUIDE", "GUIDE"
	);

	// Cost center trực tiếp của từng bộ phận (sql/02_fn_cost_actual_daily.sql)
	public static final Map<String, List<String>> DIV_COST_CENTERS = Map.of(
			"PRESS", List.of("614100"),
			"MOLD", List.of("614200"),
			"GUIDE", List.of("614600", "614700")
	);

	public static CostMonitoringSnapshot of(
			CostMonitoringChartResponse response,
			LocalDate today
	) {

		YearMonth month = YearMonth.parse(response.month());
		int n = month.lengthOfMonth();

		LocalDate latestActual = null;

		for (CostChartDto chart : response.charts()) {
			for (CostChartPointDto point : chart.points()) {
				if (point.actMtd() != null && point.date() != null
						&& (latestActual == null || point.date().isAfter(latestActual))) {
					latestActual = point.date();
				}
			}
		}

		if (latestActual != null && YearMonth.from(latestActual).equals(month)) {
			return new CostMonitoringSnapshot(response, month, today, latestActual.getDayOfMonth(), n, true);
		}

		return new CostMonitoringSnapshot(response, month, today, elapsedDays(month, today), n, false);
	}

	// Tháng hiện tại -> ngày hôm nay; tháng đã qua -> N; tháng tương lai -> 0
	private static int elapsedDays(YearMonth month, LocalDate today) {

		YearMonth current = YearMonth.from(today);

		if (month.equals(current)) {
			return today.getDayOfMonth();
		}

		return month.isBefore(current) ? month.lengthOfMonth() : 0;
	}


	// Tháng đã kết thúc: tháng đang xem < tháng hiện tại, hoặc D = N
	public boolean monthClosed() {
		return month.isBefore(YearMonth.from(today)) || d >= n;
	}


	// =========================================================
	// LOOKUP
	// =========================================================

	public CostChartDto find(String div, String cost) {

		String code = DIV_CODE.get(div);

		for (CostChartDto chart : response.charts()) {
			if (chart.div() != null
					&& chart.div().trim().toUpperCase(Locale.ROOT).equals(code)
					&& cost.equals(chart.chart())) {
				return chart;
			}
		}

		return null;
	}

	public static String divName(String divCode) {

		if (divCode == null) {
			return "";
		}

		String upper = divCode.trim().toUpperCase(Locale.ROOT);

		return DIV_NAME.getOrDefault(upper, upper);
	}


	// =========================================================
	// VALUE
	// =========================================================

	// Fluct của cả tháng = giá trị khác null cuối cùng (nhãn đỏ cuối đường trên chart)
	public static Double fluct(CostChartDto chart) {

		Double value = null;

		for (CostChartPointDto point : chart.points()) {
			if (point.fluctTgt() != null) {
				value = point.fluctTgt();
			}
		}

		return value;
	}

	// Act MTD tại ngày day (null nếu ngày đó chưa có actual)
	public Double actAt(CostChartDto chart, int day) {

		if (day < 1 || day > n) {
			return null;
		}

		LocalDate date = month.atDay(day);

		for (CostChartPointDto point : chart.points()) {
			if (date.equals(point.date())) {
				return point.actMtd();
			}
		}

		return null;
	}

	// Ngày (trong tháng) mới nhất khung này có Act MTD; chưa có -> 0
	public int lastActualDay(CostChartDto chart) {

		int day = 0;

		for (CostChartPointDto point : chart.points()) {
			if (point.actMtd() != null && point.date() != null
					&& YearMonth.from(point.date()).equals(month)) {
				day = Math.max(day, point.date().getDayOfMonth());
			}
		}

		return day;
	}

	// Estimate cuối tháng; tháng đã qua (Est null) -> Actual cả tháng
	public static Double estimate(CostChartDto chart) {
		return chart.estMonthEnd() != null ? chart.estMonthEnd() : chart.actMtdLatest();
	}

	public static String fmt(Double value) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}

		return String.format(Locale.ROOT, "%.1f", value);
	}
}
