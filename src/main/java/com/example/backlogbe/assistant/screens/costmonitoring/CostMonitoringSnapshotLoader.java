package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.logging.RequestTiming;
import com.example.backlogbe.service.CostMonitoringService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// =========================================================
// Lấy chart Cost Monitoring qua service hiện có theo tham số màn hình.
//
// screenParams:
//   month    : yyyy-MM hoặc yyyy.MM (bỏ trống = tháng hiện tại)
//   simuWeek : Simu_W1..Simu_W5 (bỏ trống = tuần Simu mới nhất có số)
//   plTgtMode: tên khác của simuWeek
//
// CACHE trong bộ nhớ (dùng chung cho context / tool trợ lý, /api/cost-monitoring/chart và /detail -> 3 nơi cùng số):
//   key   = (month đã chuẩn hóa, simuWeek, hôm nay) – sang ngày mới thì tự thành key mới (D, monthClosed đổi theo ngày)
//   TTL   = cost-monitoring.snapshot-cache-ttl-seconds (mặc định 180 giây; ≤ 0 = tắt cache)
//   Lỗi (vd month / simuWeek sai -> IllegalArgumentException) không được cache.
//   refresh = true: luôn load lại và ghi đè entry (kể cả khi TTL chưa hết).
//   Snapshot / response chỉ đọc, dùng chung giữa các request.
//   Request đồng thời cùng key khi cache trống / hết hạn: chỉ 1 request chạy SP, các request khác chờ và dùng chung kết quả.
// =========================================================
@Slf4j
@Component
public class CostMonitoringSnapshotLoader {

	// Dọn entry hết hạn khi số entry vượt mức này
	private static final int CLEANUP_THRESHOLD = 50;

	private final CostMonitoringService costMonitoringService;
	private final long ttlNanos;

	private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

	// Khóa theo key cho single-flight (xem load)
	private final Map<CacheKey, Object> locks = new ConcurrentHashMap<>();

	public CostMonitoringSnapshotLoader(
			CostMonitoringService costMonitoringService,
			@Value("${cost-monitoring.snapshot-cache-ttl-seconds:180}") long ttlSeconds
	) {
		this.costMonitoringService = costMonitoringService;
		this.ttlNanos = Math.max(0, ttlSeconds) * 1_000_000_000L;
		log.info("Cost monitoring snapshot cache ttlSeconds={}", Math.max(0, ttlSeconds));
	}

	public CostMonitoringSnapshot load(Map<String, String> screenParams) {

		return load(
				normalizeMonth(screenParams.get("month")),
				simuWeek(screenParams)
		);
	}

	// month: yyyy-MM (null = tháng hiện tại)
	public CostMonitoringSnapshot load(String month, String simuWeek) {
		return load(month, simuWeek, false);
	}

	// refresh = true: bỏ qua cache, load lại từ DB và ghi đè cache (param refresh=true của /chart, /detail)
	public CostMonitoringSnapshot load(String month, String simuWeek, boolean refresh) {

		LocalDate today = today();

		if (ttlNanos <= 0) {
			return CostMonitoringSnapshot.of(costMonitoringService.getChart(month, simuWeek), today);
		}

		CacheKey key = new CacheKey(
				month == null || month.isBlank() ? YearMonth.from(today).toString() : month.trim(),
				simuWeek == null || simuWeek.isBlank() ? null : simuWeek.trim(),
				today
		);

		CacheEntry entry = cache.get(key);

		if (!refresh && isFresh(entry)) {
			return entry.snapshot();
		}

		// Single-flight theo key: request đồng thời cùng key chờ 1 lần chạy SP thay vì cùng chạy
		long requestedAt = System.nanoTime();

		synchronized (locks.computeIfAbsent(key, k -> new Object())) {

			entry = cache.get(key);

			// refresh: chỉ dùng lại kết quả được load SAU khi request này bắt đầu (do request khác vừa refresh)
			if (isFresh(entry) && (!refresh || entry.loadedAtNanos() >= requestedAt)) {
				log.info("Cost monitoring snapshot: rid={} month={} cache=WAIT waitMs={}",
						RequestTiming.rid(), key.month(), RequestTiming.elapsedMs(requestedAt));
				return entry.snapshot();
			}

			long spStart = System.nanoTime();

			CostMonitoringSnapshot snapshot =
					CostMonitoringSnapshot.of(costMonitoringService.getChart(month, simuWeek), today);

			// Thời gian chạy sp_CostMonitoringChart + map (đối chiếu với SET STATISTICS TIME trên DB)
			log.info("Cost monitoring snapshot: rid={} month={} simuWeek={} cache={} spMs={} waitMs={}",
					RequestTiming.rid(), key.month(), key.simuWeek(), refresh ? "REFRESH" : "MISS",
					RequestTiming.elapsedMs(spStart), (spStart - requestedAt) / 1_000_000L);

			cache.put(key, new CacheEntry(snapshot, System.nanoTime()));

			if (cache.size() > CLEANUP_THRESHOLD) {
				long cutoff = System.nanoTime();
				cache.entrySet().removeIf(e -> cutoff - e.getValue().loadedAtNanos() >= ttlNanos);
				locks.keySet().retainAll(cache.keySet());
			}

			return snapshot;
		}
	}

	// Dữ liệu nguồn vừa đổi (vd nạp OT – CostOtImportService): bỏ toàn bộ cache, request sau chạy lại SP
	public void invalidateAll() {
		cache.clear();
	}

	private boolean isFresh(CacheEntry entry) {
		return entry != null && System.nanoTime() - entry.loadedAtNanos() < ttlNanos;
	}

	private record CacheKey(String month, String simuWeek, LocalDate today) {
	}

	private record CacheEntry(CostMonitoringSnapshot snapshot, long loadedAtNanos) {
	}

	public LocalDate today() {
		return LocalDate.now();
	}

	// Tháng màn hình đang xem (dùng khi tool cần tháng mà không cần chart)
	public YearMonth month(Map<String, String> screenParams) {

		String month = normalizeMonth(screenParams.get("month"));

		if (month == null) {
			return YearMonth.from(today());
		}

		try {
			return YearMonth.parse(month);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("param=month|expected=yyyy-MM");
		}
	}

	public static String simuWeek(Map<String, String> screenParams) {
		return firstNonBlank(screenParams.get("simuWeek"), screenParams.get("plTgtMode"));
	}

	// "2026.10" (dạng của /months) -> "2026-10"; dùng chung cho context, tool và gợi ý
	public static String normalizeMonth(String month) {

		if (month == null || month.isBlank()) {
			return null;
		}

		return month.trim().replace('.', '-');
	}

	private static String firstNonBlank(String a, String b) {

		if (a != null && !a.isBlank()) {
			return a;
		}

		return b != null && !b.isBlank() ? b : null;
	}
}
