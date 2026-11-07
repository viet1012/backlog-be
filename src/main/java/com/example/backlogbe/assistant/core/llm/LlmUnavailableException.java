package com.example.backlogbe.assistant.core.llm;

// LLM không gọi được, timeout, lỗi HTTP hoặc trả nội dung rỗng -> 503
// Message chỉ chứa thông tin kỹ thuật (status, loại lỗi), không chứa nội dung hội thoại.
public class LlmUnavailableException extends RuntimeException {

	public LlmUnavailableException(String message) {
		super(message);
	}

	public LlmUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
