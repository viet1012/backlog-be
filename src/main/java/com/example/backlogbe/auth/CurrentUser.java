package com.example.backlogbe.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

// =========================================================
// NGƯỜI DÙNG HIỆN TẠI (request attribute, do ProductionControlAuthFilter đặt)
//
// tokenAuthenticated = true : mã NV lấy từ session (Authorization: Bearer).
// tokenAuthenticated = false: chỉ có khi auth.enforce=false – mã NV tạm lấy từ header X-Employee-Id (chưa xác thực).
// =========================================================
public record CurrentUser(String empCode, boolean tokenAuthenticated) {

	static final String ATTRIBUTE = CurrentUser.class.getName();

	public static Optional<CurrentUser> of(HttpServletRequest request) {

		Object value = request.getAttribute(ATTRIBUTE);

		return value instanceof CurrentUser user ? Optional.of(user) : Optional.empty();
	}

	// Mã NV hoặc null (chưa đăng nhập)
	public static String empCodeOrNull(HttpServletRequest request) {
		return of(request).map(CurrentUser::empCode).orElse(null);
	}

	static void set(HttpServletRequest request, CurrentUser user) {
		request.setAttribute(ATTRIBUTE, user);
	}
}
