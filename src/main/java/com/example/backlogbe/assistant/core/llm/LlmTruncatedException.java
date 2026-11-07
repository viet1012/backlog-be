package com.example.backlogbe.assistant.core.llm;

// Content rỗng do hết max_tokens (finish_reason = length) -> 503 LLM_TRUNCATED
public class LlmTruncatedException extends LlmUnavailableException {

	public LlmTruncatedException() {
		super("LLM returned empty content, finish_reason=length");
	}
}
