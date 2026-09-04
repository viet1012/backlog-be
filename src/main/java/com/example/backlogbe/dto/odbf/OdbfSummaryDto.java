package com.example.backlogbe.dto.odbf;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OdbfSummaryDto(
		String productGrp,
		String status2,
		LocalDateTime exportD,
		Long countPo,
		BigDecimal sumQty
) {
}