package com.example.backlogbe.dto.costmonitoring;

import java.util.List;

/*
 * 1 chart = 1 cặp (div, chart).
 * fcTgt / plTgt / bp: hằng trong tháng (lấy từ dòng đầu).
 * actMtdLatest: Act_MTD khác null cuối cùng.
 * estMonthEnd: Est_MTD của ngày cuối tháng (null nếu không có).
 * forecast: dự báo / trạng thái do BE tính (CostChartForecastDto); chỉ có trong response GET /chart
 *   (controller gắn vào; snapshot trong cache để null).
 */
public record CostChartDto(
		String div,
		String chart,
		Double fcTgt,
		Double plTgt,
		Double bp,
		Double actMtdLatest,
		Double estMonthEnd,
		List<CostChartPointDto> points,
		CostChartForecastDto forecast
) {

	public CostChartDto withForecast(CostChartForecastDto value) {
		return new CostChartDto(div, chart, fcTgt, plTgt, bp, actMtdLatest, estMonthEnd, points, value);
	}
}
