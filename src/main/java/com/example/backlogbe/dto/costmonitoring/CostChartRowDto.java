package com.example.backlogbe.dto.costmonitoring;

import java.time.LocalDate;

/*
 * 1 dòng của [KVH].[dbo].[sp_CostMonitoringChart]: 1 chart / 1 ngày.
 * Các cột số giữ NULL (không ép về 0). Đơn vị K$.
 */
public record CostChartRowDto(
		String div,
		String chart,
		LocalDate d,
		Double fcTgt,
		Double plTgt,
		Double bp,
		Double plMtd,
		Double actMtd,
		Double estMtd,
		Double fluctTgt
) {
}
