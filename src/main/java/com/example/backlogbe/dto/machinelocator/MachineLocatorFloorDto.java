package com.example.backlogbe.dto.machinelocator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

// Chi tiết floor: metadata + LayoutData (crop, page, ppp, m) + version.
// GET /floors/{id} và mọi lệnh ghi đều trả về DTO này.
public record MachineLocatorFloorDto(
		String id,
		String name,
		String drawingTitle,
		String imageUrl,
		JsonNode crop,
		List<Double> page,
		Double ppp,
		JsonNode exportMeta,
		int version,
		Map<String, MachineLocatorMachineDto> m
) {
}
