package com.example.backlogbe.dto.backlog;

import java.util.List;

public record BacklogExcelExportRequest(
		BacklogFilterRequest filter,
		String search,
		String sort,
		List<String> columns
) {
}
