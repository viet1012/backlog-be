package com.example.backlogbe.dto.facconfirm;

import java.math.BigDecimal;

public record FacConfirmProcessGroupDto(

		String processGroup,

		Long requiredOrderCount,

		BigDecimal requiredTotalQty,

		Long confirmedOrderCount,

		BigDecimal confirmedTotalQty

) {
}