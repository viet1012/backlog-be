package com.example.backlogbe.dto.facconfirm;

import java.time.LocalDate;
import java.util.List;

public record FacConfirmDailySummaryDto(

		LocalDate from,

		LocalDate to,

		String dateBy,

		List<Day> days,

		Total total

) {

	public record Day(
			LocalDate date,
			FacConfirmDailyCountDto rough,
			FacConfirmDailyCountDto heat,
			FacConfirmDailyCountDto fine
	) {
	}

	public record Total(
			FacConfirmDailyCountDto rough,
			FacConfirmDailyCountDto heat,
			FacConfirmDailyCountDto fine
	) {
	}
}
