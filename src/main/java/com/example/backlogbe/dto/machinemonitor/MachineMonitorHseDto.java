package com.example.backlogbe.dto.machinemonitor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/*
 * GET /api/machine-monitor/hse.
 * machines: key = mã máy, chỉ gồm máy có ít nhất 1 lỗi (sau lọc safeFrom / safeTo).
 * unassigned: lỗi không gắn máy thuộc division của floor.
 */
public record MachineMonitorHseDto(
		String floorId,
		LocalDateTime generatedAt,
		MachineMonitorHseSummaryDto summary,
		Map<String, MachineMonitorHseMachineDto> machines,
		List<MachineMonitorHseUnassignedDto> unassigned
) {
}
