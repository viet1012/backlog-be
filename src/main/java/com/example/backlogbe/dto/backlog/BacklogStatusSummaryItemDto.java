package com.example.backlogbe.dto.backlog;


import java.math.BigDecimal;

public record BacklogStatusSummaryItemDto(
		String status,
		long poCount,
		BigDecimal totalQty
) {
}