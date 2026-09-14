package com.example.backlogbe.dto.auth;

public record ProductionControlRegisterRequest(
		String employeeId,
		String password
) {
}