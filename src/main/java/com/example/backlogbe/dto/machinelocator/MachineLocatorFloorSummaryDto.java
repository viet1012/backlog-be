package com.example.backlogbe.dto.machinelocator;

import com.fasterxml.jackson.databind.JsonNode;

// Phần tử của GET /floors. exportMeta = null nếu chưa có.
public record MachineLocatorFloorSummaryDto(
		String id,
		String name,
		String drawingTitle,
		String imageUrl,
		JsonNode exportMeta
) {
}
