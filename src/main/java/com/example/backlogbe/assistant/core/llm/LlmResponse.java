package com.example.backlogbe.assistant.core.llm;

import com.example.backlogbe.assistant.core.api.dto.ToolCall;

import java.util.List;

// content: đã bỏ reasoning, có thể rỗng (nhất là khi có toolCalls)
// promptTokens: usage.prompt_tokens của lần gọi (null nếu server không trả)
public record LlmResponse(
		String content,
		List<ToolCall> toolCalls,
		String finishReason,
		Integer promptTokens
) {

	public LlmResponse(String content, List<ToolCall> toolCalls, String finishReason) {
		this(content, toolCalls, finishReason, null);
	}

	public boolean hasToolCalls() {
		return toolCalls != null && !toolCalls.isEmpty();
	}
}
