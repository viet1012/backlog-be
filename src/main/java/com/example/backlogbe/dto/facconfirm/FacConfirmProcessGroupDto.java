//package com.example.backlogbe.dto.facconfirm;
//
//import java.math.BigDecimal;
//
//public record FacConfirmProcessGroupDto(
//		String processGroup,
//		Long orderCount,
//		BigDecimal totalFinalQty
//) {
//}
package com.example.backlogbe.dto.facconfirm;

import java.math.BigDecimal;

public record FacConfirmProcessGroupDto(
		String processGroup,
		Long orderCount,
		BigDecimal totalFinalQty,
		Long confirmCount
) {
}