package com.example.backlogbe.dto.machinelocator;

import java.util.List;

// PUT /floors/{id}/machines/{code}
// p / fit nằm trong từng box (MachineLocatorBoxRequest).
public record MachineLocatorSaveRequest(
		List<MachineLocatorBoxRequest> boxes,
		Boolean keepStatus,
		Integer ifVersion
) {
}
