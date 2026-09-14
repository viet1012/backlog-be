package com.example.backlogbe.dto.auth;

import java.util.List;

public record ProductionControlLoginResponse(
		Long accountId,
		String employeeId,
		String name,
		String fac,
		String dept,
		String section,
		String line,
		String group,
		String status,
		List<String> roles
) {
}