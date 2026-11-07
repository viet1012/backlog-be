package com.example.backlogbe.assistant.core.api;

// Client vượt số request cho phép trong cửa sổ -> 429
public class RateLimitedException extends RuntimeException {

	private final long retryAfterSeconds;

	public RateLimitedException(long retryAfterSeconds) {
		super("Rate limit exceeded");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
