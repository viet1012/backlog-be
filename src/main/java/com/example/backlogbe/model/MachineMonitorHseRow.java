package com.example.backlogbe.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * Một dòng dbo.F2_Patrol_Report. Các cột *_names giữ nguyên chuỗi.
 * machineCode = m.code khi JOIN được máy, null với lỗi không gắn máy.
 * machineText / division = giá trị trong report đã trim.
 */
public record MachineMonitorHseRow(
		String machineCode,
		String machineText,
		String division,
		String id,
		String stt,
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
		String imageNames,
		String atImageNames,
		String hseImageNames
) {
}
