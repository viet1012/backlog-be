package com.example.backlogbe.dto.facconfirm;

import com.example.backlogbe.utils.SmartDateTimeSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FacConfirmDto(

		String ferth,

		String productGrp,

		String aufnr,

		String zglobalCode,

		String pname,

		@JsonSerialize(using = SmartDateTimeSerializer.class)
		LocalDateTime issueD,

		@JsonSerialize(using = SmartDateTimeSerializer.class)
		LocalDateTime exportD,

		String cusId,

		String shipBy,

		String mtoId,

		String prtAddcmt2,

		String currentProcess,

		BigDecimal finalQty,

		@JsonSerialize(using = SmartDateTimeSerializer.class)
		LocalDateTime toDrill,

		@JsonSerialize(using = SmartDateTimeSerializer.class)
		LocalDateTime toHeat,

		@JsonSerialize(using = SmartDateTimeSerializer.class)
		LocalDateTime heatStart,

		@JsonSerialize(using = SmartDateTimeSerializer.class)
		LocalDateTime heatFinish,

		@JsonSerialize(using = SmartDateTimeSerializer.class)
		LocalDateTime toPk

) {
}