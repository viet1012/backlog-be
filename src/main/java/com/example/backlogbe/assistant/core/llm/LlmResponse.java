package com.example.backlogbe.assistant.core.llm;

import com.example.backlogbe.assistant.core.api.dto.ToolCall;

import java.util.List;

// content: đã bỏ reasoning, có thể rỗng (nhất là khi có toolCalls)
// promptTokens / completionTokens: usage.prompt_tokens / usage.completion_tokens của lần gọi (null nếu server không trả)
public record LlmResponse(
		String content,
		List<ToolCall> toolCalls,
		String finishReason,
		Integer promptTokens,
		Integer completionTokens
) {

	public LlmResponse(String content, List<ToolCall> toolCalls, String finishReason) {
		this(content, toolCalls, finishReason, null, null);
	}

	public LlmResponse(String content, List<ToolCall> toolCalls, String finishReason, Integer promptTokens) {
		this(content, toolCalls, finishReason, promptTokens, null);
	}

	public boolean hasToolCalls() {
		return toolCalls != null && !toolCalls.isEmpty();
	}
}
