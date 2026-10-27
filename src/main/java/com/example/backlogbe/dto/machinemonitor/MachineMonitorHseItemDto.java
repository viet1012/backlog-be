package com.example.backlogbe.dto.machinemonitor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// Một lỗi patrol. Cột text giữ nguyên (VN + JP, có xuống dòng).
public record MachineMonitorHseItemDto(
		String id,
		String stt,
		String status,
		String machineText,
		String division,
		String area,
		String riskTotal,
		String riskFreq,
		String riskProb,
		String riskSev,
		String comment,
		String countermeasure,
		String patrolUser,
		LocalDateTime createdAt,
		String pic,
		LocalDate dueDate,
		String atStatus,
		String atUser,
		LocalDateTime atDate,
		String atComment,
		String hseJudge,
		String hseUser,
		LocalDateTime hseDate,
		String hseComment,
		List<String> imageNames,
		List<String> atImageNames,
		List<String> hseImageNames
) {
}
