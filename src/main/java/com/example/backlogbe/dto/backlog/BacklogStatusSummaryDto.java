package com.example.backlogbe.dto.backlog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacklogStatusSummaryDto(
		long totalPoCount,
		BigDecimal totalQty,
		List<LocalDate> dates,
		List<BacklogStatusSummaryRowDto> rows
) {
}