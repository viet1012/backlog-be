package com.example.backlogbe.controller;

import com.example.backlogbe.auth.CurrentUser;
import com.example.backlogbe.dto.auth.ProductionControlLoginRequest;
import com.example.backlogbe.dto.auth.ProductionControlLoginResponse;
import com.example.backlogbe.dto.auth.ProductionControlMeResponse;
import com.example.backlogbe.dto.auth.ProductionControlRegisterRequest;
import com.example.backlogbe.dto.auth.ProductionControlRegisterResponse;
import com.example.backlogbe.service.auth.ProductionControlAuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// =========================================================
// POST /register  – chỉ khi auth.register.enabled=true (không thì 403)
// POST /login     – { employeeId, password } -> thông tin NV + roles + token + expiresAt; sai quá 5 lần / 5 phút -> 429
// POST /logout    – header Authorization: Bearer <token> -> thu hồi session (204, kể cả token không hợp lệ)
// GET  /me        – cần token hợp lệ (cả khi auth.enforce=false) -> { employeeId, name, roles[], perms{feature:[actions]} }
// =========================================================
@RestController
@RequestMapping("/api/production-control/auth")
@RequiredArgsConstructor
public class ProductionControlAuthController {

	private final ProductionControlAuthService authService;

	// =========================================================
	// REGISTER
	// =========================================================
	@PostMapping("/register")
	public ResponseEntity<ProductionControlRegisterResponse> register(
			@RequestBody ProductionControlRegisterRequest request
	) {

		return ResponseEntity.ok(
				authService.register(
						request
				)
		);
	}


	// =========================================================
	// LOGIN
	// =========================================================

	@PostMapping("/login")
	public ResponseEntity<ProductionControlLoginResponse> login(
			@RequestBody ProductionControlLoginRequest request,
			HttpServletRequest httpRequest
	) {

		return ResponseEntity.ok(
				authService.login(
						request,
						httpRequest.getRemoteAddr()
				)
		);
	}


	// =========================================================
	// LOGOUT
	// =========================================================

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			HttpServletRequest httpRequest
	) {

		String header = httpRequest.getHeader("Authorization");

		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			authService.logout(header.substring(7).trim());
		}

		return ResponseEntity.noContent().build();
	}


	// =========================================================
	// ME
	// =========================================================

	@GetMapping("/me")
	public ResponseEntity<ProductionControlMeResponse> me(
			HttpServletRequest httpRequest
	) {

		CurrentUser user = CurrentUser.of(httpRequest)
				.filter(CurrentUser::tokenAuthenticated)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.UNAUTHORIZED,
						"Login is required."
				));

		return ResponseEntity.ok(
				authService.me(user.empCode())
		);
	}
}
