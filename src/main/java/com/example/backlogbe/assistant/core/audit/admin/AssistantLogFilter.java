package com.example.backlogbe.assistant.core.audit.admin;

import com.example.backlogbe.assistant.core.audit.AssistantAuditTrace;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

// =========================================================
// Bộ lọc audit log (dùng chung list / stats / export).
//
// - from, to (yyyy-MM-dd, theo ngày trong timestamp): thiếu to -> hôm nay; thiếu from -> to - 6 ngày.
//   from > to hoặc khoảng > MAX_RANGE_DAYS ngày -> 400.
// - userId: so khớp chính xác, không phân biệt hoa thường.
// - q: chứa trong câu hỏi hoặc câu trả lời, không phân biệt hoa thường (≤ MAX_Q_LENGTH ký tự).
// - ok: true / false; source: chat | eval; minMs: totalMs ≥ minMs.
// =========================================================
public record AssistantLogFilter(
		LocalDate from,
		LocalDate to,
		String userId,
		String q,
		Boolean ok,
		String source,
		Long minMs
) {

	// Audit giữ 90 ngày (logback-spring.xml); cho phép dư vài ngày
	static final int MAX_RANGE_DAYS = 92;

	static final int DEFAULT_RANGE_DAYS = 7;

	private static final int MAX_Q_LENGTH = 200;

	public static AssistantLogFilter of(
			String from,
			String to,
			String userId,
			String q,
			Boolean ok,
			String source,
			Long minMs
	) {

		LocalDate toDate = to == null || to.isBlank() ? LocalDate.now() : parseDate("to", to);
		LocalDate fromDate = from == null || from.isBlank()
				? toDate.minusDays(DEFAULT_RANGE_DAYS - 1)
				: parseDate("from", from);

		if (fromDate.isAfter(toDate)) {
			throw new IllegalArgumentException("from must be on or before to.");
		}

		if (ChronoUnit.DAYS.between(fromDate, toDate) + 1 > MAX_RANGE_DAYS) {
			throw new IllegalArgumentException("Date range must not exceed " + MAX_RANGE_DAYS + " days.");
		}

		String safeSource = blankToNull(source);

		if (safeSource != null) {
			safeSource = safeSource.toLowerCase(Locale.ROOT);
			if (!AssistantAuditTrace.SOURCE_CHAT.equals(safeSource) && !AssistantAuditTrace.SOURCE_EVAL.equals(safeSource)) {
				throw new IllegalArgumentException("source must be chat or eval.");
			}
		}

		String safeQ = blankToNull(q);

		if (safeQ != null && safeQ.length() > MAX_Q_LENGTH) {
			throw new IllegalArgumentException("q must not exceed " + MAX_Q_LENGTH + " characters.");
		}

		if (minMs != null && minMs < 0) {
			throw new IllegalArgumentException("minMs must be >= 0.");
		}

		return new AssistantLogFilter(
				fromDate,
				toDate,
				blankToNull(userId),
				safeQ != null ? safeQ.toLowerCase(Locale.ROOT) : null,
				ok,
				safeSource,
				minMs
		);
	}

	boolean matches(AssistantLogEntry e) {

		LocalDate date = e.timestamp().toLocalDate();

		if (date.isBefore(from) || date.isAfter(to)) {
			return false;
		}
		if (userId != null && !userId.equalsIgnoreCase(e.userId())) {
			return false;
		}
		if (ok != null && ok != e.ok()) {
			return false;
		}
		if (source != null && !source.equals(e.source())) {
			return false;
		}
		if (minMs != null && (e.totalMs() == null || e.totalMs() < minMs)) {
			return false;
		}
		if (q != null && !contains(e.question(), q) && !contains(e.answer(), q)) {
			return false;
		}
		return true;
	}

	private static boolean contains(String text, String lowerNeedle) {
		return text != null && text.toLowerCase(Locale.ROOT).contains(lowerNeedle);
	}

	private static LocalDate parseDate(String name, String value) {
		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(name + " must be yyyy-MM-dd.");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
