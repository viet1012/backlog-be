package com.example.backlogbe.model;

public record ProductionControlAccount(
		Long id,
		String employeeId,
		String name,
		String fac,
		String dept,
		String section,
		String line,
		String group,
		String passwordHash,
		String status
) {
}