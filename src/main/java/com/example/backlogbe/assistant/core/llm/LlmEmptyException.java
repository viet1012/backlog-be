package com.example.backlogbe.assistant.core.llm;

// Content rỗng, finish_reason khác length (hoặc không có body) -> 503 LLM_EMPTY
public class LlmEmptyException extends LlmUnavailableException {

	public LlmEmptyException(String finishReason) {
		super("LLM returned empty content, finish_reason=" + finishReason);
	}
}
