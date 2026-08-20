package com.example.backlogbe.dto;


import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record BacklogFilterRequest(

		String search,

		String status,

		String div,

		String currentProcess,

		String shipBy,

		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		LocalDate productionDate

) {
}