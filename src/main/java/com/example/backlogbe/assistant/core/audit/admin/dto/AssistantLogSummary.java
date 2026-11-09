package com.example.backlogbe.assistant.core.audit.admin.dto;

// 1 dòng trong danh sách audit log (bản gọn). question cắt 200 ký tự; promptTokens = tổng mọi lần gọi LLM.
public record AssistantLogSummary(
		String rid,
		String timestamp,
		String userId,
		String screenKey,
		String question,
		boolean ok,
		String errorCode,
		Long totalMs,
		int llmCallCount,
		Long promptTokens
) {
}
