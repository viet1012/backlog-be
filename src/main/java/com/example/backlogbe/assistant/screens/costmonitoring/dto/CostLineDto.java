package com.example.backlogbe.assistant.screens.costmonitoring.dto;

import java.time.LocalDate;

// 1 dòng xuất kho đơn lẻ (không gộp): vật tư, ngày xuất, amount (K$)
public record CostLineDto(
		String code,
		String name,
		String category,
		LocalDate date,
		Double amountK
) {
}
