package com.example.backlogbe.assistant.core.audit.admin;

import com.example.backlogbe.assistant.core.audit.admin.dto.AssistantLogStats;
import com.example.backlogbe.assistant.core.audit.admin.dto.AssistantLogSummary;
import com.example.backlogbe.dto.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// =========================================================
// TRA CỨU AUDIT LOG TRỢ LÝ AI (admin) – đọc thẳng file JSONL, không dùng DB.
// Mỗi request đọc lại file trong khoảng ngày (≤ 92 ngày), lọc + sắp xếp trong bộ nhớ.
// =========================================================
@Service
@RequiredArgsConstructor
public class AssistantLogQueryService {

	public static final int MAX_PAGE_SIZE = 200;
	public static final int DEFAULT_PAGE_SIZE = 50;

	// Giới hạn số dòng export (.xlsx có câu trả lời đầy đủ)
	public static final int MAX_EXPORT_ROWS = 20_000;

	private static final int QUESTION_PREVIEW = 200;
	private static final int TOP_N = 10;

	private static final String SORT_TIMESTAMP = "timestamp";
	private static final String SORT_TOTAL_MS = "totalMs";

	// rid của RequestTimingFilter: 8 ký tự hex; eval ghi "-" (không tra theo rid được)
	private static final Pattern RID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	private final AssistantLogFileReader reader;


	// =========================================================
	// LIST
	// =========================================================

	public PageResponse<AssistantLogSummary> list(
			AssistantLogFilter filter,
			String sort,
			String order,
			Integer page,
			Integer size
	) {

		int safePage = page == null ? 0 : page;
		int safeSize = size == null ? DEFAULT_PAGE_SIZE : size;

		if (safePage < 0) {
			throw new IllegalArgumentException("page must be >= 0.");
		}
		if (safeSize < 1 || safeSize > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
		}

		Comparator<AssistantLogEntry> comparator = comparator(sort, order);

		List<AssistantLogEntry> entries = load(filter);
		entries.sort(comparator);

		int fromIndex = (int) Math.min((long) safePage * safeSize, entries.size());
		int toIndex = Math.min(fromIndex + safeSize, entries.size());

		List<AssistantLogSummary> content = entries.subList(fromIndex, toIndex).stream()
				.map(AssistantLogQueryService::summary)
				.toList();

		return PageResponse.of(content, safePage, safeSize, entries.size());
	}


	// =========================================================
	// DETAIL
	// =========================================================

	public JsonNode detail(String rid) {

		String safeRid = rid == null ? "" : rid.trim();

		if ("-".equals(safeRid) || !RID.matcher(safeRid).matches()) {
			throw new IllegalArgumentException("Invalid rid.");
		}

		return reader.findByRid(safeRid)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Log not found."));
	}


	// =========================================================
	// STATS
	// =========================================================

	public AssistantLogStats stats(AssistantLogFilter filter) {

		List<AssistantLogEntry> entries = load(filter);

		long errorCount = entries.stream().filter(e -> !e.ok()).count();

		List<Long> durations = entries.stream()
				.map(AssistantLogEntry::totalMs)
				.filter(ms -> ms != null)
				.sorted()
				.toList();

		return new AssistantLogStats(
				filter.from().toString(),
				filter.to().toString(),
				entries.size(),
				errorCount,
				entries.isEmpty() ? 0.0 : Math.round(errorCount * 1000.0 / entries.size()) / 10.0,
				average(durations),
				percentile(durations, 50),
				percentile(durations, 95),
				byDay(filter, entries),
				topUsers(entries),
				entries.stream()
						.filter(e -> e.totalMs() != null)
						.sorted(Comparator.comparing(AssistantLogEntry::totalMs).reversed()
								.thenComparing(AssistantLogEntry::timestamp, Comparator.reverseOrder()))
						.limit(TOP_N)
						.map(AssistantLogQueryService::summary)
						.toList()
		);
	}

	private static List<AssistantLogStats.DayStat> byDay(AssistantLogFilter filter, List<AssistantLogEntry> entries) {

		Map<LocalDate, List<AssistantLogEntry>> grouped = new HashMap<>();

		for (AssistantLogEntry e : entries) {
			grouped.computeIfAbsent(e.timestamp().toLocalDate(), d -> new ArrayList<>()).add(e);
		}

		List<AssistantLogStats.DayStat> result = new ArrayList<>();

		for (LocalDate d = filter.from(); !d.isAfter(filter.to()); d = d.plusDays(1)) {

			List<AssistantLogEntry> day = grouped.getOrDefault(d, List.of());

			result.add(new AssistantLogStats.DayStat(
					d.toString(),
					day.size(),
					day.stream().filter(e -> !e.ok()).count(),
					average(day.stream().map(AssistantLogEntry::totalMs).filter(ms -> ms != null).toList())
			));
		}

		return result;
	}

	private static List<AssistantLogStats.UserStat> topUsers(List<AssistantLogEntry> entries) {

		// Gom không phân biệt hoa thường, hiển thị theo cách viết gặp đầu tiên
		Map<String, String> display = new HashMap<>();
		Map<String, Long> counts = new LinkedHashMap<>();

		for (AssistantLogEntry e : entries) {
			if (e.userId() == null || e.userId().isBlank()) {
				continue;
			}
			String key = e.userId().toUpperCase(Locale.ROOT);
			display.putIfAbsent(key, e.userId());
			counts.merge(key, 1L, Long::sum);
		}

		return counts.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(TOP_N)
				.map(en -> new AssistantLogStats.UserStat(display.get(en.getKey()), en.getValue()))
				.toList();
	}

	private static Long average(List<Long> values) {
		if (values.isEmpty()) {
			return null;
		}
		return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
	}

	// Nearest-rank trên danh sách đã sắp tăng dần
	private static Long percentile(List<Long> sorted, int p) {
		if (sorted.isEmpty()) {
			return null;
		}
		int rank = (int) Math.ceil(p / 100.0 * sorted.size());
		return sorted.get(Math.max(rank, 1) - 1);
	}


	// =========================================================
	// EXPORT
	// =========================================================

	public record ExportPlan(AssistantLogFilter filter, List<AssistantLogEntry> rows, int totalMatched) {

		public boolean truncated() {
			return totalMatched > rows.size();
		}
	}

	// Đọc + lọc trước khi trả response (lỗi tham số -> 400 thay vì file hỏng)
	public ExportPlan planExport(AssistantLogFilter filter, String sort, String order) {

		Comparator<AssistantLogEntry> comparator = comparator(sort, order);

		List<AssistantLogEntry> entries = load(filter);
		entries.sort(comparator);

		List<AssistantLogEntry> rows = entries.size() > MAX_EXPORT_ROWS
				? new ArrayList<>(entries.subList(0, MAX_EXPORT_ROWS))
				: entries;

		return new ExportPlan(filter, rows, entries.size());
	}


	// =========================================================
	// COMMON
	// =========================================================

	private List<AssistantLogEntry> load(AssistantLogFilter filter) {

		List<AssistantLogEntry> result = new ArrayList<>();

		reader.forEach(filter.from(), filter.to(), e -> {
			if (filter.matches(e)) {
				result.add(e);
			}
		});

		return result;
	}

	// sort: timestamp | totalMs (mặc định timestamp); order: asc | desc (mặc định desc).
	// Chấp nhận cả dạng sort=totalMs,asc. Cùng giá trị -> timestamp mới trước.
	static Comparator<AssistantLogEntry> comparator(String sort, String order) {

		String field = sort == null || sort.isBlank() ? SORT_TIMESTAMP : sort.trim();
		String direction = order;

		int comma = field.indexOf(',');
		if (comma >= 0) {
			direction = field.substring(comma + 1);
			field = field.substring(0, comma).trim();
		}

		direction = direction == null || direction.isBlank() ? "desc" : direction.trim().toLowerCase(Locale.ROOT);

		if (!direction.equals("asc") && !direction.equals("desc")) {
			throw new IllegalArgumentException("order must be asc or desc.");
		}

		boolean asc = direction.equals("asc");

		Comparator<AssistantLogEntry> byTimestampDesc =
				Comparator.comparing(AssistantLogEntry::timestamp, Comparator.reverseOrder());

		if (SORT_TIMESTAMP.equalsIgnoreCase(field)) {
			Comparator<AssistantLogEntry> c = Comparator.comparing(AssistantLogEntry::timestamp);
			return asc ? c : c.reversed();
		}

		if (SORT_TOTAL_MS.equalsIgnoreCase(field)) {
			// Bản ghi thiếu totalMs luôn ở cuối
			Comparator<Long> ms = asc ? Comparator.naturalOrder() : Comparator.reverseOrder();
			return Comparator.comparing(AssistantLogEntry::totalMs, Comparator.nullsLast(ms))
					.thenComparing(byTimestampDesc);
		}

		throw new IllegalArgumentException("sort must be timestamp or totalMs.");
	}

	private static AssistantLogSummary summary(AssistantLogEntry e) {
		return new AssistantLogSummary(
				e.rid(),
				e.timestamp().toString(),
				e.userId(),
				e.screenKey(),
				truncate(e.question(), QUESTION_PREVIEW),
				e.ok(),
				e.errorCode(),
				e.totalMs(),
				e.llmCallCount(),
				e.promptTokens()
		);
	}

	private static String truncate(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		return text.substring(0, max) + "…";
	}
}
