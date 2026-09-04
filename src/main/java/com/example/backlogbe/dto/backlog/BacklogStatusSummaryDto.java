package com.example.backlogbe.dto.backlog;

import java.math.BigDecimal;
import java.util.List;

public record BacklogStatusSummaryDto(
		long totalPoCount,
		BigDecimal totalQty,
		List<BacklogStatusSummaryItemDto> statuses
) {
}