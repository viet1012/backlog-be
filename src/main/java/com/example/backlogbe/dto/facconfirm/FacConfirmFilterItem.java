package com.example.backlogbe.dto.facconfirm;

import java.util.List;

public record FacConfirmFilterItem(

		String field,

		String operator,

		String value,

		List<String> values

) {
}