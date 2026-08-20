package com.example.backlogbe.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ShipmentFulfillmentDto(

		LocalDateTime exportD,

		String cusId,

		String shipBy,

		BigDecimal poQty,

		BigDecimal fnQty,

		BigDecimal fnRatio

) {
}