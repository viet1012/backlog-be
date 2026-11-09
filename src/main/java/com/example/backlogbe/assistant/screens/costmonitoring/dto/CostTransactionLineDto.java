package com.example.backlogbe.assistant.screens.costmonitoring.dto;

import java.time.LocalDate;

// 1 dòng xuất kho (CostTool / CostRm) đúng như bảng nguồn, amount chưa chia 1000
public record CostTransactionLineDto(
		LocalDate date,
		String code,
		String name,
		String category,
		Double qty,
		String qtyUnit,
		Double unitPrice,
		Double amount,
		String costCenter,
		String costCenterName,
		String machine
) {
}
