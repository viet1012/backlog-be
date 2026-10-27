package com.example.backlogbe.dto.machinelocator;

// POST /floors/{id}/machines/{code}/rename
public record MachineLocatorRenameRequest(
		String to,
		Integer ifVersion
) {
}
