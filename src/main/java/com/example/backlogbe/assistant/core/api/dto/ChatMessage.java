package com.example.backlogbe.assistant.core.api.dto;

import java.util.List;

/*
 * 1 message trong hội thoại. role: system | user | assistant | tool
 * toolCalls : chỉ có ở message assistant yêu cầu gọi tool.
 * toolCallId: chỉ có ở message tool (kết quả tool).
 * FE chỉ gửi role + content trong history; 2 field còn lại bị bỏ khi sanitize.
 */
public record ChatMessage(
		String role,
		String content,
		List<ToolCall> toolCalls,
		String toolCallId
) {

	public static final String SYSTEM = "system";
	public static final String USER = "user";
	public static final String ASSISTANT = "assistant";
	public static final String TOOL = "tool";

	public ChatMessage(String role, String content) {
		this(role, content, null, null);
	}

	public static ChatMessage system(String content) {
		return new ChatMessage(SYSTEM, content);
	}

	public static ChatMessage user(String content) {
		return new ChatMessage(USER, content);
	}

	public static ChatMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
		return new ChatMessage(ASSISTANT, content, toolCalls, null);
	}

	public static ChatMessage toolResult(String toolCallId, String content) {
		return new ChatMessage(TOOL, content, null, toolCallId);
	}
}
