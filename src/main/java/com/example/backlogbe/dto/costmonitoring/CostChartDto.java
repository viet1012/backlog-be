package com.example.backlogbe.dto.costmonitoring;

import java.util.List;

/*
 * 1 chart = 1 cặp (div, chart).
 * fcTgt / plTgt / bp: hằng trong tháng (lấy từ dòng đầu).
 * actMtdLatest: Act_MTD khác null cuối cùng.
 * estMonthEnd: Est_MTD của ngày cuối tháng (null nếu không có).
 */
public record CostChartDto(
		String div,
		String chart,
		Double fcTgt,
		Double plTgt,
		Double bp,
		Double actMtdLatest,
		Double estMonthEnd,
		List<CostChartPointDto> points
) {
}
