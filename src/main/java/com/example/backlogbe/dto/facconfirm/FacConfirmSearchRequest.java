package com.example.backlogbe.dto.facconfirm;

import java.time.LocalDate;
import java.util.List;

public record FacConfirmSearchRequest(
		String div,
		LocalDate expD,
		String procGrp,
		String classify,
		Integer page,
		Integer size,
		List<FacConfirmFilterItem> filters,
		String logicOperator
) {
}