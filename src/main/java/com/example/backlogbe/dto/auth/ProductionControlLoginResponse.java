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
		List<String> roles,
		// Token đăng nhập (AuthTokenService): gửi "Authorization: Bearer <token>" cho API cần danh tính thật
		String token,
		// Hạn token, ISO-8601 UTC
		String tokenExpiresAt
) {
}