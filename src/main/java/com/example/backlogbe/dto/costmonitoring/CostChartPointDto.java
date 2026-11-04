package com.example.backlogbe.dto.costmonitoring;

import java.time.LocalDate;

// 1 điểm trên trục X (ngày trong tháng), giá trị lũy kế MTD
public record CostChartPointDto(
		LocalDate date,
		Double plMtd,
		Double actMtd,
		Double estMtd,
		Double fluctTgt
) {
}
