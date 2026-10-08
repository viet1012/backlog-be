package com.example.backlogbe.dto.facconfirm;

import java.time.LocalDateTime;
import java.util.List;

public record FacConfirmProcessTimeRequest(
		String employeeId,
		List<ProcessTimeItem> changes,

		// Tùy chọn: Rough / Heat / Fine.
		// Có thì kiểm tra field theo đúng công đoạn (FacConfirmEditRules).
		String procGrp
) {

	public record ProcessTimeItem(
			String aufnr,
			String field,
			LocalDateTime value
	) {
	}
}