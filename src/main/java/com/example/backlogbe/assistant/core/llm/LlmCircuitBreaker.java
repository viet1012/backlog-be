package com.example.backlogbe.assistant.core.llm;

import com.example.backlogbe.assistant.core.config.AssistantProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// =========================================================
// NGẮT NHANH KHI LLM TREO (circuit breaker đơn giản)
//
// Lý do (audit 2026-11-11): LLM server treo từ 09:41 – mọi câu hỏi cần LLM chờ đủ 170 s rồi LLM_ERROR
// ("Bạn là ai?", "OT của PRESS…"), FE báo "mất kết nối" sau ~3 phút, giữ suất đồng thời của người khác.
//
//   Đóng (bình thường) : gọi LLM như cũ.
//   ≥ threshold lỗi LLM liên tiếp (LlmUnavailableException: timeout / HTTP / không kết nối) -> MỞ trong openSeconds:
//                        allow() = false -> ChatOrchestrator ném LlmUnavailableException ngay (503 LLM_ERROR, < 1 s).
//   Hết openSeconds     : cho đúng 1 request thử (half-open); thành công -> đóng; lỗi -> mở lại.
// Câu trả lời template không đi qua đây. threshold ≤ 0 -> tắt.
// Log chung chỉ ghi trạng thái mạch (không ghi câu hỏi).
// =========================================================
@Slf4j
@Component
public class LlmCircuitBreaker {

	private final int threshold;
	private final long openNanos;

	private final AtomicInteger consecutiveFailures = new AtomicInteger();
	private final AtomicLong openUntilNanos = new AtomicLong(0);
	private final AtomicBoolean trialInFlight = new AtomicBoolean(false);
	private final AtomicLong trialStartedNanos = new AtomicLong(0);

	public LlmCircuitBreaker(AssistantProperties props) {
		this.threshold = props.getLlm().getCircuitFailureThreshold();
		this.openNanos = Math.max(0, props.getLlm().getCircuitOpenSeconds()) * 1_000_000_000L;
	}

	// false = mạch đang mở, không gọi LLM
	public boolean allow() {

		if (threshold <= 0 || consecutiveFailures.get() < threshold) {
			return true;
		}

		if (System.nanoTime() < openUntilNanos.get()) {
			return false;
		}

		// Hết thời gian mở: chỉ 1 request thử; request thử không báo kết quả (lỗi trước khi gọi LLM) quá openNanos
		// thì cho request khác thử, tránh kẹt mở mãi
		long now = System.nanoTime();
		if (trialInFlight.compareAndSet(false, true)) {
			trialStartedNanos.set(now);
			return true;
		}
		if (now - trialStartedNanos.get() > openNanos) {
			trialStartedNanos.set(now);
			return true;
		}
		return false;
	}

	public void recordSuccess() {

		if (consecutiveFailures.getAndSet(0) >= threshold && threshold > 0) {
			log.info("Assistant: LLM circuit closed");
		}
		trialInFlight.set(false);
	}

	public void recordFailure() {

		int failures = consecutiveFailures.incrementAndGet();
		trialInFlight.set(false);

		if (threshold > 0 && failures >= threshold) {
			openUntilNanos.set(System.nanoTime() + openNanos);
			log.warn("Assistant: LLM circuit open failures={} openSeconds={}", failures, openNanos / 1_000_000_000L);
		}
	}

	// Giám sát / test
	public boolean isOpen() {
		return threshold > 0 && consecutiveFailures.get() >= threshold && System.nanoTime() < openUntilNanos.get();
	}
}
