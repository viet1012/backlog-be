package com.example.backlogbe.dto.costmonitoring;

import java.util.List;

/*
 * GET /api/cost-monitoring/chart.
 * month: yyyy-MM. charts: thứ tự Div PR, MO, Guide × Chart OT, T&SL, RM.
 */
public record CostMonitoringChartResponse(
		String month,
		String simuWeek,
		String unit,
		List<CostChartDto> charts
) {

	public static final String UNIT = "K$";
}
