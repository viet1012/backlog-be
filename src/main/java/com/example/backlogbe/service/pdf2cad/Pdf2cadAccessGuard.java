package com.example.backlogbe.service.pdf2cad;

import com.example.backlogbe.service.auth.AuthTokenService;
import com.example.backlogbe.service.auth.ProductionControlAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

// =========================================================
// Quyền dùng /api/pdf2cad/**
//
// pdf2cad.auth-required = true:
//   Danh tính: "Authorization: Bearer <token>" do login cấp (AuthTokenService, ký HMAC, có hạn).
//   Role: tính lại từ DB mỗi request, phải có ít nhất 1 role trong pdf2cad.allowed-roles.
//   Không có / sai / hết hạn token -> 401 (FE yêu cầu đăng nhập lại); không đủ quyền -> 403.
//
// pdf2cad.auth-required = false (TẠM THỜI, mặc định): không chặn request nào.
//   Danh tính (chỉ để ghi nhật ký + chủ job): token hợp lệ nếu có, không thì header X-Employee-Id
//   (client tự khai, KHÔNG xác minh), không có gì -> "anonymous".
// =========================================================
@Component
@RequiredArgsConstructor
public class Pdf2cadAccessGuard {

	public static final String MSG_LOGIN = "Phiên đăng nhập đã hết hạn, hãy đăng nhập lại";
	public static final String MSG_FORBIDDEN = "Tài khoản không có quyền dùng PDF → CAD";
	public static final String ANONYMOUS = "anonymous";

	private final AuthTokenService tokenService;

	private final ProductionControlAuthService authService;

	private final Pdf2cadProperties properties;

	/** @return MSNV của người gọi (đã xác thực khi auth-required = true) */
	public String requireAccess(String authorization, String employeeHeader) {

		Optional<String> fromToken = tokenService.verifyBearer(authorization);

		if (!properties.isAuthRequired()) {
			return fromToken.orElseGet(() -> declaredEmployeeId(employeeHeader));
		}

		String employeeId = fromToken
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, MSG_LOGIN));

		List<String> roles = authService.findAccountRoles(employeeId).orElse(List.of());

		if (roles.stream().noneMatch(properties.getAllowedRoles()::contains)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, MSG_FORBIDDEN);
		}

		return employeeId;
	}

	private static String declaredEmployeeId(String header) {
		if (header == null) return ANONYMOUS;
		String cleaned = header.trim().replaceAll("[^0-9A-Za-z_.-]", "");
		return cleaned.isEmpty() ? ANONYMOUS : cleaned.substring(0, Math.min(cleaned.length(), 20));
	}
}
