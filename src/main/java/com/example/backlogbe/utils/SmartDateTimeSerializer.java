package com.example.backlogbe.utils;


import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class SmartDateTimeSerializer extends JsonSerializer<LocalDateTime> {

	private static final DateTimeFormatter DATE_ONLY =
			DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final DateTimeFormatter DATE_TIME =
			DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

	@Override
	public void serialize(
			LocalDateTime value,
			JsonGenerator gen,
			SerializerProvider serializers
	) throws IOException {

		if (value == null) {
			gen.writeNull();
			return;
		}

		if (value.getHour() == 0
				&& value.getMinute() == 0
				&& value.getSecond() == 0
				&& value.getNano() == 0) {

			gen.writeString(value.format(DATE_ONLY));

		} else {

			gen.writeString(value.format(DATE_TIME));
		}
	}
}