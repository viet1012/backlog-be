package com.example.backlogbe.assistant.core.llm;

import com.example.backlogbe.assistant.core.config.AssistantProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

// =========================================================
// Giới hạn số câu hỏi xử lý đồng thời (Semaphore(maxConcurrent)).
//
// Giữ 1 suất cho cả vòng tool calling của 1 câu hỏi (nhiều lần gọi LLM),
// để không bị 503 giữa chừng. Đầy -> LlmBusyException ngay, không chờ.
// =========================================================
@Component
public class LlmConcurrencyLimiter {

	private final Semaphore semaphore;

	public LlmConcurrencyLimiter(AssistantProperties props) {
		this.semaphore = new Semaphore(Math.max(1, props.getMaxConcurrent()));
	}

	public <T> T run(Supplier<T> task) {

		if (!semaphore.tryAcquire()) {
			throw new LlmBusyException();
		}

		try {
			return task.get();
		} finally {
			semaphore.release();
		}
	}
}
