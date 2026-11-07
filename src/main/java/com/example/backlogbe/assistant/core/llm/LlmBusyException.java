package com.example.backlogbe.assistant.core.llm;

// Đã đủ maxConcurrent request đang chạy -> 503
public class LlmBusyException extends RuntimeException {

	public LlmBusyException() {
		super("LLM concurrency limit reached");
	}
}
