package com.example.backlogbe.dto.facconfirm;

import java.time.LocalDateTime;
import java.util.List;

public record FacConfirmProcessTimeRequest(
		// Không dùng khi có phiên đăng nhập (lấy từ CurrentUser);
		// chỉ dùng tạm khi auth.enforce=false và request không có token.
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