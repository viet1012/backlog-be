package com.example.backlogbe.dto.facconfirm;

import java.time.LocalDate;
import java.util.List;

public record FacConfirmProcessGroupRequest(

		String div,

		LocalDate expD,

		String classify,

		String heatType,

		List<FacConfirmFilterItem> filters,

		String logicOperator

) {
}