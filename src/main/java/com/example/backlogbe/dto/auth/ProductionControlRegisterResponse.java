package com.example.backlogbe.dto.auth;

public record ProductionControlRegisterResponse(
		Long accountId,
		String employeeId,
		String status,
		String message
) {
}