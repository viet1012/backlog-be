package com.example.backlogbe.model;

import java.time.LocalDateTime;

// Một dòng dbo.F2_MachineLocator_Floor (các cột *_json giữ nguyên chuỗi JSON).
public record MachineLocatorFloorRow(
		String id,
		String name,
		String drawingTitle,
		String imageUrl,
		String cropJson,
		Double pageW,
		Double pageH,
		Double ppp,
		String exportMetaJson,
		int version,
		LocalDateTime updatedAt,
		String updatedBy
) {
}
