package com.example.backlogbe.dto.backlog;

import java.util.List;

public record BacklogFilterItem(

		String field,

		String operator,

		String value,

		List<String> values

) {
}