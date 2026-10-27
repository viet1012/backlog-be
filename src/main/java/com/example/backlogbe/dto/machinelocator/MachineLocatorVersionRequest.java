package com.example.backlogbe.dto.machinelocator;

// POST /floors/{id}/machines/{code}/confirm | /revert
public record MachineLocatorVersionRequest(
		Integer ifVersion
) {
}
