package com.example.backlogbe.assistant.core.api;

import com.example.backlogbe.assistant.core.config.AssistantProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// =========================================================
// RATE LIMIT /api/assistant/chat
//
// Fixed window theo khóa client (mã nhân viên nếu có, không thì IP): tối đa N request / 60 s.
// Hai hạn mức (assistant.rate-limit.*):
//   check(key)    : mọi request (per-minute) – controller gọi trước khi xử lý.
//   checkLlm(key) : chỉ request đi tới LLM (llm-per-minute) – ChatOrchestrator gọi sau khi biết không có câu trả lời
//                   template; 0 = tắt.
// Lưu trong bộ nhớ (ConcurrentHashMap), mỗi instance tính riêng, restart là reset.
// Entry hết hạn được dọn định kỳ để map không phình.
// =========================================================
@Component
public class AssistantRateLimiter {

	static final long WINDOW_MS = 60_000;

	// Dọn entry hết hạn tối đa 1 lần / cửa sổ
	private static final long CLEANUP_INTERVAL_MS = WINDOW_MS;

	private record Window(long startMs, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final Map<String, Window> llmWindows = new ConcurrentHashMap<>();

	private final AssistantProperties props;

	private volatile long lastCleanupMs = System.currentTimeMillis();

	public AssistantRateLimiter(AssistantProperties props) {
		this.props = props;
	}

	/**
	 * Khóa giới hạn: mã nhân viên (đã kiểm định dạng) nếu có, không thì IP client.
	 */
	public static String clientKey(String employeeId, String remoteAddr) {
		if (employeeId != null && !employeeId.isBlank()) {
			return "emp:" + employeeId.trim();
		}
		return "ip:" + (remoteAddr != null ? remoteAddr : "unknown");
	}

	/**
	 * Tính 1 request (mọi loại) cho clientKey.
	 *
	 * @throws RateLimitedException nếu vượt giới hạn
	 */
	public void check(String clientKey) {
		count(windows, clientKey, props.getRateLimit().getPerMinute());
	}

	/**
	 * Tính 1 request gọi LLM cho clientKey (câu trả lời template không tính).
	 *
	 * @throws RateLimitedException nếu vượt giới hạn
	 */
	public void checkLlm(String clientKey) {
		count(llmWindows, clientKey, props.getRateLimit().getLlmPerMinute());
	}

	private void count(Map<String, Window> map, String clientKey, int max) {

		if (max <= 0) {
			return;
		}

		long now = System.currentTimeMillis();
		String key = clientKey != null ? clientKey : "unknown";

		cleanupIfDue(now);

		// compute chạy nguyên tử theo key
		Window window = map.compute(key, (k, w) ->
				w == null || now - w.startMs() >= WINDOW_MS
						? new Window(now, 1)
						: new Window(w.startMs(), w.count() + 1)
		);

		if (window.count() > max) {
			long remainingMs = WINDOW_MS - (now - window.startMs());
			throw new RateLimitedException(Math.max(1, (remainingMs + 999) / 1000));
		}
	}

	private void cleanupIfDue(long now) {

		if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) {
			return;
		}

		lastCleanupMs = now;
		windows.entrySet().removeIf(e -> now - e.getValue().startMs() >= WINDOW_MS);
		llmWindows.entrySet().removeIf(e -> now - e.getValue().startMs() >= WINDOW_MS);
	}
}
