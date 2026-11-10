package com.example.backlogbe.assistant.core.llm;

import com.example.backlogbe.assistant.core.config.AssistantProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// =========================================================
// Giới hạn số câu hỏi xử lý đồng thời (Semaphore(maxConcurrent)) + hàng đợi ngắn.
//
// Giữ 1 suất cho cả vòng tool calling của 1 câu hỏi (nhiều lần gọi LLM), để không bị 503 giữa chừng.
// Đầy suất: chờ tối đa assistant.llm.queue-timeout-seconds (FIFO theo Semaphore fair) thay vì trả 503 ngay
// (audit 2026-11-10: nhiều tab cùng hỏi -> 503 liên tục dù LLM chỉ bận vài giây).
// Số người chờ > assistant.llm.max-queue -> 503 ngay (quá tải thật sự). Hết giờ chờ -> 503 LLM_BUSY.
// Câu trả lời template không đi qua đây.
// =========================================================
@Slf4j
@Component
public class LlmConcurrencyLimiter {

	private final Semaphore semaphore;
	private final long queueTimeoutMs;
	private final int maxQueue;
	private final AtomicInteger waiting = new AtomicInteger();

	public LlmConcurrencyLimiter(AssistantProperties props) {
		this.semaphore = new Semaphore(Math.max(1, props.getLlm().getMaxConcurrent()), true);
		this.queueTimeoutMs = Math.max(0, props.getLlm().getQueueTimeoutSeconds()) * 1000L;
		this.maxQueue = Math.max(0, props.getLlm().getMaxQueue());
	}

	public <T> T run(Supplier<T> task) {

		if (!acquire()) {
			throw new LlmBusyException();
		}

		try {
			return task.get();
		} finally {
			semaphore.release();
		}
	}

	private boolean acquire() {

		if (semaphore.tryAcquire()) {
			return true;
		}

		if (queueTimeoutMs <= 0) {
			return false;
		}

		if (waiting.incrementAndGet() > maxQueue) {
			waiting.decrementAndGet();
			log.warn("Assistant: LLM queue full waiting={} maxQueue={}", waiting.get(), maxQueue);
			return false;
		}

		try {
			boolean ok = semaphore.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
			if (!ok) {
				log.warn("Assistant: LLM queue timeout after {} ms", queueTimeoutMs);
			}
			return ok;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			waiting.decrementAndGet();
		}
	}

	// Số câu hỏi đang chờ suất (log / giám sát)
	public int waiting() {
		return waiting.get();
	}

	// Số suất còn trống
	public int available() {
		return semaphore.availablePermits();
	}
}
