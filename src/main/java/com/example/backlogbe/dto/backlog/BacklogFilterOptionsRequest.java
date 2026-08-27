package com.example.backlogbe.dto.backlog;


import java.util.List;

public record BacklogFilterOptionsRequest(

		String field,

		List<BacklogFilterItem> filters,

		String logicOperator,

		String search,

		Integer limit

) {
}