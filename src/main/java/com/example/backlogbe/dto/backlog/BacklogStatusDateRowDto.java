package com.example.backlogbe.dto.backlog;

import java.util.List;

public record BacklogStatusDateRowDto(
		String status,
		List<BacklogStatusDateCellDto> values
) {
}