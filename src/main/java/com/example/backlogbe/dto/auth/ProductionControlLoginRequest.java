package com.example.backlogbe.dto.auth;

public record ProductionControlLoginRequest(
		String employeeId,
		String password
) {
}