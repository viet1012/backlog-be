package com.example.backlogbe.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

// =========================================================
// GIỚI HẠN LOGIN SAI: tối đa 5 lần trong 5 phút cho mỗi (employeeId + IP), giữ trong bộ nhớ.
// Login đúng -> xóa bộ đếm của khóa đó. Restart BE / nhiều instance -> đếm riêng từng instance.
// IP = remoteAddr (không tin X-Forwarded-For vì client tự đặt được).
// =========================================================
@Component
public class LoginRateLimiter {

	static final int MAX_FAILURES = 5;
	private static final long WINDOW_MS = 5 * 60 * 1000L;
	private static final int CLEANUP_SIZE = 10_000;

	private final ConcurrentHashMap<String, Deque<Long>> failures = new ConcurrentHashMap<>();

	public static String key(String employeeId, String clientIp) {
		return (employeeId == null ? "" : employeeId) + "|" + (clientIp == null ? "" : clientIp);
	}

	public boolean isBlocked(String key) {

		Deque<Long> times = failures.get(key);

		if (times == null) {
			return false;
		}

		synchronized (times) {
			prune(times, System.currentTimeMillis());
			return times.size() >= MAX_FAILURES;
		}
	}

	public void recordFailure(String key) {

		long now = System.currentTimeMillis();

		if (failures.size() >= CLEANUP_SIZE) {
			cleanup(now);
		}

		Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());

		synchronized (times) {
			prune(times, now);
			times.addLast(now);
		}
	}

	public void reset(String key) {
		failures.remove(key);
	}

	private void cleanup(long now) {
		failures.entrySet().removeIf(e -> {
			Deque<Long> times = e.getValue();
			synchronized (times) {
				prune(times, now);
				return times.isEmpty();
			}
		});
	}

	private static void prune(Deque<Long> times, long now) {
		while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
			times.pollFirst();
		}
	}
}
