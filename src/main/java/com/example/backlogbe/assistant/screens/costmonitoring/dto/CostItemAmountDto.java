package com.example.backlogbe.assistant.screens.costmonitoring.dto;

// Tổng amount (K$) của 1 vật tư (Code + Text) trong 1 khoảng ngày
public record CostItemAmountDto(
		String code,
		String name,
		Double amountK
) {
}
