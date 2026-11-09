package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.suggestion.SuggestionCondition;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartForecastDto;
import com.example.backlogbe.dto.costmonitoring.CostPlWeeksResponse;
import com.example.backlogbe.service.CostMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// =========================================================
// ĐIỀU KIỆN CHO CÂU GỢI Ý ĐỘNG – COST MONITORING
//
// Theo từng khung (CostMonitoringAssessment):
//   over           Act > Fluct                        tier 0, weight = Act − Fluct
//   forecast_over  chưa vượt, dự báo cuối tháng CHÍNH (PL Estimate; không PL thì Act + Fluct / N × (N − D)) > Fluct
//                  tier 1, weight = estimateOver. Cùng trạng thái với /chart (CostMonitoringAssessment.mainStatus).
//   watch          Rủi ro "Cần theo dõi"              tier 2, weight = Pct − Pace
//   no_actual      chưa có Actual                     tier 3
// Theo tháng:
//   no_pl          tháng chưa có PL simulation nào    tier 3 (không có {div} / {cost})
// Theo bộ phận (Ratio dùng chung cho OT / T&SL / RM nên chỉ có {div}):
//   fluct_below_fc / fluct_above_fc   |Fluct / FC − 1| ≥ 3%   tier 3, weight = |Fluct / FC − 1|
// Greeting: đếm khung "over" / "forecast_over" (xem greeting()).
// Dùng qua CostMonitoringModule.suggestionConditions / suggestionGreeting.
// =========================================================
@Component
@RequiredArgsConstructor
public class CostMonitoringSuggestionConditions {

	private static final double FLUCT_FC_THRESHOLD = 0.03;

	private static final int TIER_OVER = 0;
	private static final int TIER_FORECAST_OVER = 1;
	private static final int TIER_WATCH = 2;
	private static final int TIER_OTHER = 3;

	private final CostMonitoringSnapshotLoader loader;
	private final CostMonitoringService costMonitoringService;

	public List<SuggestionCondition> conditions(Map<String, String> params) {

		Map<String, String> safeParams = params != null ? params : Map.of();

		// month "2026-11" hoặc "2026.11" – cùng hàm parse với context provider; sai định dạng -> 400
		YearMonth month = loader.month(safeParams);

		CostMonitoringSnapshot snapshot = loader.load(
				month.toString(),
				CostMonitoringSnapshotLoader.simuWeek(safeParams)
		);

		List<SuggestionCondition> result = new ArrayList<>();

		for (CostChartDto chart : snapshot.response().charts()) {

			// Tháng đã kết thúc: không có forecast_over / watch (assessment không tính dự báo, tiến độ)
			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
			String div = CostMonitoringSnapshot.divName(chart.div());
			String cost = chart.chart();

			// Cùng trạng thái chính với /chart (CostMonitoringAssessment.mainStatus): OVER, rồi WILL_EXCEED theo PL Estimate
			String status = a.mainStatus();

			if (CostChartForecastDto.STATUS_OVER.equals(status)) {
				result.add(new SuggestionCondition("over", div, cost, TIER_OVER, a.act() - a.fluct()));
			} else if (CostChartForecastDto.STATUS_WILL_EXCEED.equals(status)) {
				result.add(new SuggestionCondition("forecast_over", div, cost, TIER_FORECAST_OVER,
						a.estimateOver() != null ? a.estimateOver() : 0));
			}

			if (CostMonitoringAssessment.RISK_WATCH.equals(a.risk())) {
				result.add(new SuggestionCondition("watch", div, cost, TIER_WATCH,
						a.pct() != null && a.pace() != null ? a.pct() - a.pace() : 0));
			}

			if (a.act() == null) {
				result.add(new SuggestionCondition("no_actual", div, cost, TIER_OTHER, 0));
			}
		}

		CostPlWeeksResponse plWeeks = costMonitoringService.getPlWeeks(snapshot.response().month());

		if (plWeeks.weeks().isEmpty()) {
			result.add(new SuggestionCondition("no_pl", null, null, TIER_OTHER, 0));
		}

		for (String div : CostMonitoringSnapshot.DIVS) {

			Double ratio = fluctFcRatio(snapshot, div);

			if (ratio != null && Math.abs(ratio) >= FLUCT_FC_THRESHOLD) {
				result.add(new SuggestionCondition(
						ratio < 0 ? "fluct_below_fc" : "fluct_above_fc",
						div,
						null,
						TIER_OTHER,
						Math.abs(ratio)
				));
			}
		}

		return result;
	}

	/*
	 * Greeting theo dữ liệu (ưu tiên từ trên xuống):
	 *   có khung "over"          -> "Có x khung đã vượt target, xem nhé!"
	 *   có khung "forecast_over" -> "Có x khung dự báo vượt, cần chú ý!"
	 *   còn lại                  -> "Chi phí tháng này đang ổn!"
	 */
	public String greeting(List<SuggestionCondition> conditions, String lang) {

		long over = conditions.stream().filter(c -> "over".equals(c.when())).count();
		long forecastOver = conditions.stream().filter(c -> "forecast_over".equals(c.when())).count();
		boolean en = CmText.isEn(lang);

		if (over > 0) {
			return en
					? over + (over == 1 ? " chart is" : " charts are") + " already over target, take a look!"
					: "Có " + over + " khung đã vượt target, xem nhé!";
		}

		if (forecastOver > 0) {
			return en
					? forecastOver + (forecastOver == 1 ? " chart is" : " charts are") + " forecast to exceed target, heads up!"
					: "Có " + forecastOver + " khung dự báo vượt, cần chú ý!";
		}

		return en ? "Costs are on track this month!" : "Chi phí tháng này đang ổn!";
	}

	// Fluct / FC − 1 của bộ phận: lấy khung đầu tiên có đủ FC và Fluct (Ratio giống nhau cho 3 loại chi phí)
	private static Double fluctFcRatio(CostMonitoringSnapshot snapshot, String div) {

		for (String cost : CostMonitoringSnapshot.COSTS) {

			CostChartDto chart = snapshot.find(div, cost);

			if (chart == null) {
				continue;
			}

			Double fc = chart.fcTgt();
			Double fluct = CostMonitoringSnapshot.fluct(chart);

			if (fc != null && fluct != null && fc != 0) {
				return fluct / fc - 1;
			}
		}

		return null;
	}
}
