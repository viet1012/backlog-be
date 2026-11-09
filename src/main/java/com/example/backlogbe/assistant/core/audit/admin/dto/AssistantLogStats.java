package com.example.backlogbe.assistant.core.audit.admin.dto;

import java.util.List;

// Thống kê audit log theo bộ lọc.
// errorRate: % (1 chữ số thập phân); avgMs / p50Ms / p95Ms: chỉ tính bản ghi có totalMs (null nếu không có).
public record AssistantLogStats(
		String from,
		String to,
		long totalQuestions,
		long errorCount,
		double errorRate,
		Long avgMs,
		Long p50Ms,
		Long p95Ms,
		List<DayStat> byDay,
		List<UserStat> topUsers,
		List<AssistantLogSummary> slowest
) {

	// Mọi ngày trong [from, to], kể cả ngày không có câu hỏi (count = 0, avgMs = null)
	public record DayStat(String date, long count, long errorCount, Long avgMs) {
	}

	// Không tính bản ghi không có userId
	public record UserStat(String userId, long count) {
	}
}
