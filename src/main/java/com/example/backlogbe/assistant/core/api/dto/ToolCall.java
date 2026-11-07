package com.example.backlogbe.assistant.core.api.dto;

// 1 lời gọi tool do model yêu cầu (OpenAI tools format). arguments: chuỗi JSON.
public record ToolCall(
		String id,
		String name,
		String arguments
) {
}
