package com.example.backlogbe.logging;

import org.slf4j.MDC;

// =========================================================
// REQUEST TIMING – tiện ích log thời gian theo request
//
// requestId gắn vào MDC bởi RequestTimingFilter (chỉ các path được đo).
// Các dòng log thời gian ghi "rid=<requestId>" để gom theo request; ngoài request được đo -> "-".
// =========================================================
public final class RequestTiming {

	public static final String MDC_KEY = "requestId";

	private RequestTiming() {
	}

	public static String rid() {
		String value = MDC.get(MDC_KEY);
		return value != null ? value : "-";
	}

	public static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
