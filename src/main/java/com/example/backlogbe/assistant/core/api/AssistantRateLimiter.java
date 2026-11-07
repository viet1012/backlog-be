package com.example.backlogbe.assistant.core.api;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// =========================================================
// RATE LIMIT /api/assistant/chat
//
// Fixed window theo IP client: tối đa MAX_REQUESTS request / WINDOW_MS.
// Lưu trong bộ nhớ (ConcurrentHashMap), mỗi instance tính riêng, restart là reset.
// Entry hết hạn được dọn định kỳ để map không phình.
// =========================================================
@Component
public class AssistantRateLimiter {

	static final int MAX_REQUESTS = 10;
	static final long WINDOW_MS = 60_000;

	// Dọn entry hết hạn tối đa 1 lần / cửa sổ
	private static final long CLEANUP_INTERVAL_MS = WINDOW_MS;

	private record Window(long startMs, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	private volatile long lastCleanupMs = System.currentTimeMillis();

	/**
	 * Tính 1 request cho clientKey.
	 *
	 * @throws RateLimitedException nếu vượt giới hạn
	 */
	public void check(String clientKey) {

		long now = System.currentTimeMillis();
		String key = clientKey != null ? clientKey : "unknown";

		cleanupIfDue(now);

		// compute chạy nguyên tử theo key
		Window window = windows.compute(key, (k, w) ->
				w == null || now - w.startMs() >= WINDOW_MS
						? new Window(now, 1)
						: new Window(w.startMs(), w.count() + 1)
		);

		if (window.count() > MAX_REQUESTS) {
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
	}
}
