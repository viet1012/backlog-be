package com.example.backlogbe.dto.machinemonitor;

import java.util.List;

/*
 * status = trạng thái tệ nhất của các lỗi.
 * openCount = OPEN + NG + OVERDUE (chưa xử lý xong), overdueCount chỉ OVERDUE.
 * maxRisk = riskTotal cao nhất (I < II < III < IV < V) trong các lỗi chưa SAFE, null nếu không có.
 */
public record MachineMonitorHseMachineDto(
		String status,
		int openCount,
		int overdueCount,
		int waitCount,
		int safeCount,
		String maxRisk,
		List<MachineMonitorHseItemDto> items
) {
}
