package com.example.backlogbe.dto.machinemonitor;

/*
 * Số máy theo trạng thái máy (tổng open..safe = machines).
 * unassignedOpen = số lỗi chưa SAFE không gắn máy.
 */
public record MachineMonitorHseSummaryDto(
		int machines,
		int open,
		int overdue,
		int ng,
		int waitHse,
		int safe,
		int unassignedOpen
) {
}
