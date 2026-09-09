package com.example.backlogbe.dto.backlog;

import java.util.List;

public record BacklogStatusSummaryRowDto(
		String status,
		List<BacklogStatusSummaryCellDto> values
) {
}