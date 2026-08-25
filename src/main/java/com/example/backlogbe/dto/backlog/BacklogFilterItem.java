package com.example.backlogbe.dto.backlog;


public record BacklogFilterItem(

		String field,

		String operator,

		String value

) {
}