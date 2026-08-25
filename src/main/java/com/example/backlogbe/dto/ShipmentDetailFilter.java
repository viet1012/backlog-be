package com.example.backlogbe.dto;


import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record ShipmentDetailFilter(

		String cusId,

		String shipBy,

		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		LocalDate exportDate

) {
}