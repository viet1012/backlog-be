package com.example.backlogbe.dto.backlog;

import java.util.List;

public record BacklogFilterRequest(

		List<BacklogFilterItem> filters,

		String logicOperator

) {

	public BacklogFilterRequest {

		if (filters == null) {
			filters = List.of();
		}

		if (logicOperator == null || logicOperator.isBlank()) {
			logicOperator = "and";
		}
	}
}