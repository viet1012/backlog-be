package com.example.backlogbe.dto.machinemonitor;

import java.util.List;

// Nhóm lỗi không gắn máy, gom theo machine trong report. Các field còn lại giống MachineMonitorHseMachineDto.
public record MachineMonitorHseUnassignedDto(
		String label,
		String status,
		int openCount,
		int overdueCount,
		int waitCount,
		int safeCount,
		String maxRisk,
		List<MachineMonitorHseItemDto> items
) {
}
