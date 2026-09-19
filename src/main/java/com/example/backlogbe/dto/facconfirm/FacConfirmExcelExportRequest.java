package com.example.backlogbe.dto.facconfirm;

import java.time.LocalDate;
import java.util.List;

public record FacConfirmExcelExportRequest(
		String div,
		LocalDate expD,
		String procGrp,
		String classify,
		String heatType,
		String search,
		List<FacConfirmFilterItem> filters,
		String logicOperator,
		List<String> columns
) {
}
