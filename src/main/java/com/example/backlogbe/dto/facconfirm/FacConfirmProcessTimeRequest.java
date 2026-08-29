package com.example.backlogbe.dto.facconfirm;

import java.time.LocalDateTime;
import java.util.List;

public record FacConfirmProcessTimeRequest(
		String employeeId,
		List<ProcessTimeItem> changes
) {

	public record ProcessTimeItem(
			String aufnr,
			String field,
			LocalDateTime value
	) {
	}
}