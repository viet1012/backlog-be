package com.example.backlogbe.assistant.core.audit.admin;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

// =========================================================
// 1 dòng audit đã đọc (các field chính, dùng cho list / stats / export).
// promptTokens / completionTokens: tổng qua mọi lần gọi LLM (null nếu không lần nào có số).
// tools: tên tool pre-fetch + tool model gọi, không trùng, nối bằng ", ".
// params: JSON thô của field params (null nếu không có).
// =========================================================
record AssistantLogEntry(
		String rid,
		OffsetDateTime timestamp,
		String source,
		String screenKey,
		String lang,
		String userId,
		String params,
		String question,
		String answer,
		boolean ok,
		String errorCode,
		String errorMessage,
		Long totalMs,
		Long contextMs,
		Long askedMonthsMs,
		Long prefetchMs,
		int llmCallCount,
		Long promptTokens,
		Long completionTokens,
		String tools,
		String model,
		String reasoningEffort
) {

	// null nếu dòng không phải object hoặc timestamp thiếu / sai dạng
	static AssistantLogEntry from(JsonNode n) {

		if (n == null || !n.isObject()) {
			return null;
		}

		OffsetDateTime timestamp;

		try {
			String ts = text(n, "timestamp");
			if (ts == null) {
				return null;
			}
			timestamp = OffsetDateTime.parse(ts);
		} catch (DateTimeParseException e) {
			return null;
		}

		JsonNode llmCalls = n.path("llmCalls");
		JsonNode params = n.get("params");

		return new AssistantLogEntry(
				text(n, "rid"),
				timestamp,
				text(n, "source"),
				text(n, "screenKey"),
				text(n, "lang"),
				text(n, "userId"),
				params == null || params.isNull() ? null : params.toString(),
				text(n, "question"),
				text(n, "answer"),
				n.path("ok").asBoolean(false),
				text(n, "errorCode"),
				text(n, "errorMessage"),
				number(n, "totalMs"),
				number(n, "contextMs"),
				number(n, "askedMonthsMs"),
				number(n, "prefetchMs"),
				n.hasNonNull("llmCallCount") ? n.get("llmCallCount").asInt() : (llmCalls.isArray() ? llmCalls.size() : 0),
				sum(llmCalls, "promptTokens"),
				sum(llmCalls, "completionTokens"),
				toolNames(n),
				text(n, "model"),
				text(n, "reasoningEffort")
		);
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}

	private static Long number(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v != null && v.isNumber() ? v.asLong() : null;
	}

	private static Long sum(JsonNode array, String field) {

		if (!array.isArray()) {
			return null;
		}

		Long total = null;

		for (JsonNode item : array) {
			JsonNode v = item.get(field);
			if (v != null && v.isNumber()) {
				total = (total == null ? 0L : total) + v.asLong();
			}
		}

		return total;
	}

	private static String toolNames(JsonNode n) {

		Set<String> names = new LinkedHashSet<>();

		for (String field : new String[]{"prefetchTools", "toolCalls"}) {
			JsonNode array = n.path(field);
			if (array.isArray()) {
				for (JsonNode item : array) {
					String name = text(item, "name");
					if (name != null) {
						names.add(name);
					}
				}
			}
		}

		return names.isEmpty() ? null : String.join(", ", names);
	}
}
