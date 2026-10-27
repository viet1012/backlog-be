package com.example.backlogbe.dto.machinelocator;

import java.util.List;

// POST /floors/{id}/bulk
public record MachineLocatorBulkRequest(
		List<Op> ops,
		Boolean keepStatus,
		Integer ifVersion
) {

	// type: 'set' | 'del'. boxes chỉ dùng cho 'set' (p / fit nằm trong từng box).
	public record Op(
			String type,
			String code,
			List<MachineLocatorBoxRequest> boxes
	) {
	}
}
