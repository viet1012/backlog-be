package com.example.backlogbe.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

// =========================================================
// Đường dẫn không qua xác thực (filter) / kiểm quyền (interceptor).
// Chỉ /api/** được kiểm tra; ngoài /api (static, /error…) bỏ qua.
// =========================================================
final class AuthPaths {

	static final String API_PREFIX = "/api/";

	static final Set<String> PUBLIC_PATHS = Set.of(
			"/api/production-control/auth/login",
			"/api/production-control/auth/register",
			// logout tự đọc token (kể cả token đã hết hạn) – không chặn ở filter
			"/api/production-control/auth/logout",
			"/api/health",
			"/health",
			"/actuator/health"
	);

	private AuthPaths() {
	}

	static String path(HttpServletRequest request) {
		return request.getRequestURI().substring(request.getContextPath().length());
	}

	static boolean isPublic(HttpServletRequest request) {

		String path = path(request);

		return "OPTIONS".equalsIgnoreCase(request.getMethod())
				|| !path.startsWith(API_PREFIX)
				|| PUBLIC_PATHS.contains(path);
	}

	// "Authorization: Bearer <token>" -> token; thiếu / sai dạng -> null
	static String bearerToken(HttpServletRequest request) {

		String header = request.getHeader("Authorization");

		if (header == null || header.length() < 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return null;
		}

		String token = header.substring(7).trim();

		return token.isEmpty() ? null : token;
	}
}
