package com.example.backlogbe.dto.facconfirm;

import java.time.LocalDate;
import java.util.List;

public record FacConfirmFilterOptionsRequest(
		String field,
		String search,
		String div,
		LocalDate expD,
		String procGrp,
		String classify,
		String heatType,
		List<FacConfirmFilterItem> filters
) {
}