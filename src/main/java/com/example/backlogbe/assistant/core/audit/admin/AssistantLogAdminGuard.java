package com.example.backlogbe.assistant.core.audit.admin;

import com.example.backlogbe.service.auth.ProductionControlAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// =========================================================
// Chỉ ADMIN: role tính như login (HSE_Patrol_Account + HR, ProductionControlAuthService.findAccountRoles),
// mã nhân viên lấy từ header X-Employee-Id (cùng cách Machine Locator).
// Thiếu header -> 401; không có tài khoản hoặc không phải ADMIN -> 403.
// Lưu ý: header do FE tự gửi, BE chưa xác thực (xem docs/API_AUDIT.md).
// =========================================================
@Component
@RequiredArgsConstructor
class AssistantLogAdminGuard {

	private static final String ROLE_ADMIN = "ADMIN";

	private final ProductionControlAuthService authService;

	void requireAdmin(String employeeId) {

		if (employeeId == null || employeeId.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required.");
		}

		List<String> roles = authService.findAccountRoles(employeeId).orElse(List.of());

		if (!roles.contains(ROLE_ADMIN)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only ADMIN can view assistant logs.");
		}
	}
}
