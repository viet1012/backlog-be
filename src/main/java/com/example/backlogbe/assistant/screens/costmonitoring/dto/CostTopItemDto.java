package com.example.backlogbe.assistant.screens.costmonitoring.dto;

// Top vật tư theo amount (K$) trong 1 khoảng ngày. qty = tổng Qty theo qtyUnit.
public record CostTopItemDto(
		String code,
		String name,
		String category,
		Double qty,
		String qtyUnit,
		Double amountK
) {
}
