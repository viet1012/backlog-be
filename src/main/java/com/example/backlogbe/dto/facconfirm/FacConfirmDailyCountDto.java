package com.example.backlogbe.dto.facconfirm;

import java.math.BigDecimal;

// Số liệu một công đoạn: "Cần xác nhận" (need) / "Đã xác nhận" (done)
public record FacConfirmDailyCountDto(

		long needPo,

		BigDecimal needPcs,

		long donePo,

		BigDecimal donePcs

) {

	public static final FacConfirmDailyCountDto ZERO =
			new FacConfirmDailyCountDto(
					0L,
					BigDecimal.ZERO,
					0L,
					BigDecimal.ZERO
			);
}
