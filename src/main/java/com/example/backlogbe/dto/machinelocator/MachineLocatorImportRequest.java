package com.example.backlogbe.dto.machinelocator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

// POST /floors/{id}/import: body là nguyên file LayoutData của FE.
public record MachineLocatorImportRequest(
		JsonNode crop,
		List<Double> page,
		Double ppp,
		Map<String, MachineLocatorMachineDto> m
) {
}
