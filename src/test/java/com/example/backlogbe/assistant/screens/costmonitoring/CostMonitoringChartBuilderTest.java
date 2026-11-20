package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.api.dto.AssistantChart;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CostMonitoringChartBuilderTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

	// Actual tăng đều perDay đến ngày 10, Estimate đến cuối tháng, Fluct cố định
	private static CostChartDto chart(String div, double perDay, double fluct, double fc) {
		List<CostChartPointDto> points = new ArrayList<>();
		for (int day = 1; day <= 30; day++) {
			Double act = day <= 10 ? perDay * day : null;
			Double est = day > 10 ? perDay * 10 + perDay * 1.1 * (day - 10) : null;
			points.add(new CostChartPointDto(LocalDate.of(2026, 11, day), null, act, est, fluct));
		}
		return new CostChartDto(div, "RM", fc, null, null, perDay * 10, perDay * 10 + perDay * 1.1 * 20, points, null);
	}

	private static CostMonitoringChartBuilder builder() {
		CostMonitoringChartResponse response = new CostMonitoringChartResponse("2026-11", null, "K$",
				List.of(chart("PR", 20, 500, 480), chart("MO", 3, 100, 95)));
		CostMonitoringSnapshotLoader loader = mock(CostMonitoringSnapshotLoader.class);
		when(loader.load(any(Map.class))).thenReturn(CostMonitoringSnapshot.of(response, TODAY));
		return new CostMonitoringChartBuilder(loader);
	}

	@Test
	void baseIntentStripsCombosAndSkipsOtherMonths() {
		assertEquals("overview", CostMonitoringChartBuilder.baseIntent("overview+anomaly_today"));
		assertEquals("forecast_one", CostMonitoringChartBuilder.baseIntent("forecast_one_current_rate"));
		assertNull(CostMonitoringChartBuilder.baseIntent("overview_previous_month"));
		assertNull(CostMonitoringChartBuilder.baseIntent("range_summary"));
		assertNull(CostMonitoringChartBuilder.baseIntent(null));
	}

	@Test
	void remainingBudgetComparesCurrentRateWithMaxPerDay() {
		List<AssistantChart> charts = builder().build(CostMonitoringLookupAnswers.REMAINING_BUDGET,
				"RM mỗi ngày còn được chi tối đa bao nhiêu?", Map.of("month", "2026-11"), "vi");

		assertEquals(1, charts.size());
		AssistantChart chart = charts.get(0);
		assertEquals(AssistantChart.HBAR, chart.type());
		assertEquals(List.of("PRESS RM", "MOLD RM"), chart.categories());
		// PRESS RM: (500 − 200) / 20 ngày = 15/ngày, đang chi 20/ngày -> vượt
		assertEquals(20.0, chart.series().get(0).data().get(0), 1e-9);
		assertEquals(15.0, chart.series().get(1).data().get(0), 1e-9);
		assertEquals(AssistantChart.Series.OVER, chart.series().get(0).status().get(0));
		// MOLD RM: (100 − 30) / 20 = 3.5/ngày, đang chi 3/ngày -> ổn
		assertEquals(AssistantChart.Series.OK, chart.series().get(0).status().get(1));
	}

	@Test
	void trendOfOneFrameIsCumulativeLine() {
		List<AssistantChart> charts = builder().build(CostMonitoringLookupAnswers.TREND,
				"Xu hướng chi PRESS RM", Map.of("month", "2026-11"), "vi");

		assertEquals(1, charts.size());
		AssistantChart chart = charts.get(0);
		assertEquals(AssistantChart.LINE, chart.type());
		assertEquals(30, chart.categories().size());
		List<String> kinds = chart.series().stream().map(AssistantChart.Series::kind).toList();
		assertEquals(List.of("actual", "estimate", "target", "fc"), kinds);
		// Actual dừng ở ngày 10, Estimate nối từ ngày 10
		assertEquals(200.0, chart.series().get(0).data().get(9), 1e-9);
		assertNull(chart.series().get(0).data().get(10));
		assertEquals(200.0, chart.series().get(1).data().get(9), 1e-9);
	}

	@Test
	void overviewIsForecastPercentOfFluctSortedDescending() {
		List<AssistantChart> charts = builder().build("overview", "Tình hình RM tháng này", Map.of("month", "2026-11"), "vi");

		assertEquals(1, charts.size());
		AssistantChart chart = charts.get(0);
		assertEquals(AssistantChart.HBAR, chart.type());
		assertEquals(100.0, chart.refValue());
		// PRESS: (200 + 440) / 500 = 128% ; MOLD: (30 + 66) / 100 = 96%
		assertEquals(List.of("PRESS RM", "MOLD RM"), chart.categories());
		assertEquals(128.0, chart.series().get(0).data().get(0), 1e-9);
		assertTrue(chart.series().get(0).data().get(1) < 100);
	}

	@Test
	void anomalyTodayChartIsOverrunInK$() {
		List<AssistantChart> charts = builder().build("overview+anomaly_today", "Hôm nay có gì bất thường RM", Map.of("month", "2026-11"), "vi");

		assertEquals(1, charts.size());
		AssistantChart chart = charts.get(0);
		assertEquals("K$", chart.unit());
		assertNull(chart.refValue());
		// PRESS: 640 − 500 = +140 ; MOLD: 96 − 100 = −4 (còn dư)
		assertEquals(List.of("PRESS RM", "MOLD RM"), chart.categories());
		assertEquals(140.0, chart.series().get(0).data().get(0), 1e-9);
		assertEquals(-4.0, chart.series().get(0).data().get(1), 1e-9);
		assertEquals(List.of(AssistantChart.Series.OVER, AssistantChart.Series.OK), chart.series().get(0).status());
	}

	@Test
	void unknownIntentHasNoChart() {
		assertTrue(builder().build("item_most_issued", "Vật tư nào xuất nhiều nhất", Map.of(), "vi").isEmpty());
	}
}
