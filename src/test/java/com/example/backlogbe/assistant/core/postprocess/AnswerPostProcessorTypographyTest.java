package com.example.backlogbe.assistant.core.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnswerPostProcessorTypographyTest {

	@Test
	void replacesUnicodeHyphensInItemCodes() {
		// Câu trả lời thật trong audit log 2026-11-09 (rid 2413be30)
		assertEquals("MICROMETER/MDC-25MX(293-230-30), CBN WHEEL/MI-DB011, tháng 2026-11",
				AnswerPostProcessor.normalizeTypography(
						"MICROMETER/MDC‑25MX(293‑230‑30), CBN WHEEL/MI‑DB011, tháng 2026‑11"));
	}

	@Test
	void normalizesNonBreakingSpacesAndPercent() {
		assertEquals("chiếm 6.8% tổng Actual",
				AnswerPostProcessor.normalizeTypography("chiếm 6.8 % tổng Actual"));
		assertEquals("6.8%", AnswerPostProcessor.normalizeTypography("6.8 %"));
	}

	@Test
	void keepsEnDashAndMinusSign() {
		assertEquals("ngày 1–8; Actual − Fluct",
				AnswerPostProcessor.normalizeTypography("ngày 1–8; Actual − Fluct"));
	}

	@Test
	void nullSafe() {
		assertNull(AnswerPostProcessor.normalizeTypography(null));
	}
}
