package com.example.backlogbe.dto.backlog;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BacklogStatusDateCellDto(
		LocalDate date,
		long poCount,
		BigDecimal qty
) {
}