package com.example.backlogbe.service.auth;

import com.example.backlogbe.auth.AuthProperties;
import com.example.backlogbe.auth.LoginRateLimiter;
import com.example.backlogbe.auth.ProductionControlPermissionService;
import com.example.backlogbe.auth.ProductionControlPermissionService.EmpPerms;
import com.example.backlogbe.auth.ProductionControlSessionService;
import com.example.backlogbe.auth.ProductionControlSessionService.NewSession;
import com.example.backlogbe.dto.auth.ProductionControlLoginRequest;
import com.example.backlogbe.dto.auth.ProductionControlLoginResponse;
import com.example.backlogbe.dto.auth.ProductionControlMeResponse;
import com.example.backlogbe.dto.auth.ProductionControlRegisterRequest;
import com.example.backlogbe.dto.auth.ProductionControlRegisterResponse;
import com.example.backlogbe.model.PatrolAccount;
import com.example.backlogbe.repository.auth.ProductionControlAuthRepository;
import com.example.backlogbe.repository.auth.ProductionControlAuthRepository.EmployeeOrg;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

// =========================================================
// ĐĂNG NHẬP PRODUCTION CONTROL
//
// - Mật khẩu: HSE_Patrol_Account.Pass (chưa hash – dùng chung với S-Patrol), so bằng MessageDigest.isEqual.
// - Đúng: tạo session (ProductionControlSessionService) -> token + expiresAt.
// - Role / quyền: vw_F2_ProductionControl_EmpPerm (AppCode PCW), không tự suy từ HR nữa.
// - Login sai: tối đa 5 lần / 5 phút cho mỗi (employeeId + IP) -> 429.
// - Register: chỉ khi auth.register.enabled=true, không thì 403.
// =========================================================
@Service
@RequiredArgsConstructor
public class ProductionControlAuthService {

	private static final String STATUS_ACTIVE = "ACTIVE";

	private final ProductionControlAuthRepository repository;
	private final ProductionControlSessionService sessionService;
	private final ProductionControlPermissionService permissionService;
	private final LoginRateLimiter loginRateLimiter;
	private final AuthProperties authProperties;

	// =========================================================
	// REGISTER
	// =========================================================

	@Transactional
	public ProductionControlRegisterResponse register(
			ProductionControlRegisterRequest request
	) {

		if (!authProperties.registerEnabled()) {
			throw new ResponseStatusException(
					HttpStatus.FORBIDDEN,
					"Đăng ký tạm khóa, liên hệ IT"
			);
		}

		String employeeId =
				normalizeEmployeeId(
						request.employeeId()
				);

		String password =
				request.password();

		validateRegisterInput(
				employeeId,
				password
		);


		// =====================================================
		// NHÂN VIÊN ĐANG LÀM (vw_F2_ProductionControl_Employee, IsActive = 1)
		// =====================================================

		if (!repository.existsActiveEmployee(employeeId)) {
			throw new IllegalArgumentException(
					"Employee ID does not exist in company HR data."
			);
		}


		// =====================================================
		// DUPLICATE ACCOUNT
		// =====================================================

		if (
				repository.existsPatrolAccount(
						employeeId
				)
		) {

			throw new IllegalArgumentException(
					"Employee ID already has an account. Please log in with your S-Patrol password."
			);
		}


		// =====================================================
		// CREATE ACCOUNT (HSE_Patrol_Account)
		// =====================================================

		repository.createPatrolAccount(
				employeeId,
				password
		);


		return new ProductionControlRegisterResponse(
				null,
				employeeId,
				STATUS_ACTIVE,
				"Account created successfully."
		);
	}

	// =========================================================
	// LOGIN
	// =========================================================

	@Transactional
	public ProductionControlLoginResponse login(
			ProductionControlLoginRequest request,
			String clientIp
	) {
		String employeeId = normalizeEmployeeId(
				request.employeeId()
		);

		String password = request.password();

		validateLoginInput(
				employeeId,
				password
		);

		// -----------------------------------------------------
		// Rate limit (employeeId + IP)
		// -----------------------------------------------------

		String limitKey = LoginRateLimiter.key(employeeId, clientIp);

		if (loginRateLimiter.isBlocked(limitKey)) {
			throw new ResponseStatusException(
					HttpStatus.TOO_MANY_REQUESTS,
					"Đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 5 phút."
			);
		}

		// -----------------------------------------------------
		// Password (HSE_Patrol_Account)
		// -----------------------------------------------------

		PatrolAccount patrolAccount =
				repository.findPatrolAccount(employeeId)
						.orElse(null);

		if (patrolAccount == null || !passwordMatches(password, patrolAccount.pass())) {
			loginRateLimiter.recordFailure(limitKey);
			throw invalidCredentials();
		}

		loginRateLimiter.reset(limitKey);

		// -----------------------------------------------------
		// Tên + tổ chức (vw_F2_ProductionControl_Employee; không thấy vẫn cho login, các field = null)
		// -----------------------------------------------------

		EmployeeOrg org =
				repository.findEmployeeOrg(employeeId)
						.orElse(null);

		// -----------------------------------------------------
		// Roles: đọc mới từ view (bỏ cache cũ của NV này)
		// -----------------------------------------------------

		permissionService.evict(employeeId);

		EmpPerms perms = permissionService.get(employeeId);

		// -----------------------------------------------------
		// Session + Last Login
		// -----------------------------------------------------

		NewSession session = sessionService.create(employeeId);

		repository.updatePatrolLastLogin(
				employeeId
		);

		// -----------------------------------------------------
		// Response
		// -----------------------------------------------------

		return new ProductionControlLoginResponse(
				null,
				employeeId,
				org != null ? org.name() : null,
				org != null ? org.fac() : null,
				org != null ? org.dept() : null,
				org != null ? org.section() : null,
				org != null ? org.line() : null,
				org != null ? org.group() : null,
				STATUS_ACTIVE,
				perms.roles(),
				session.token(),
				session.expiresAt() != null ? session.expiresAt().toString() : null
		);
	}

	// =========================================================
	// LOGOUT
	// =========================================================

	public void logout(String token) {
		sessionService.revoke(token);
	}

	// =========================================================
	// ME
	// =========================================================

	public ProductionControlMeResponse me(String employeeId) {

		String safeEmployeeId = normalizeEmployeeId(employeeId);

		EmployeeOrg org =
				repository.findEmployeeOrg(safeEmployeeId)
						.orElse(null);

		EmpPerms perms = permissionService.get(safeEmployeeId);

		return new ProductionControlMeResponse(
				safeEmployeeId,
				org != null ? org.name() : null,
				org != null ? org.fac() : null,
				org != null ? org.dept() : null,
				org != null ? org.section() : null,
				org != null ? org.line() : null,
				org != null ? org.group() : null,
				perms.roles(),
				perms.perms()
		);
	}

	// =========================================================
	// VALIDATE
	// =========================================================

	private void validateRegisterInput(
			String employeeId,
			String password
	) {
		if (employeeId.isBlank()) {
			throw new IllegalArgumentException(
					"Employee ID (MSNV) is required."
			);
		}

		if (password == null || password.isBlank()) {
			throw new IllegalArgumentException(
					"Password is required."
			);
		}
	}

	private void validateLoginInput(
			String employeeId,
			String password
	) {
		if (
				employeeId.isBlank()
						|| password == null
						|| password.isBlank()
		) {
			throw new IllegalArgumentException(
					"Employee ID and password are required."
			);
		}
	}

	private IllegalArgumentException invalidCredentials() {
		return new IllegalArgumentException(
				"Invalid Employee ID or password."
		);
	}

	// So sánh thời gian hằng (không lộ độ dài khớp qua thời gian phản hồi)
	private static boolean passwordMatches(String input, String stored) {

		if (stored == null) {
			return false;
		}

		return MessageDigest.isEqual(
				input.getBytes(StandardCharsets.UTF_8),
				stored.getBytes(StandardCharsets.UTF_8)
		);
	}

	// =========================================================
	// NORMALIZE
	// =========================================================

	private String normalizeEmployeeId(
			String employeeId
	) {
		if (employeeId == null) {
			return "";
		}

		return employeeId
				.trim()
				.toUpperCase(Locale.ROOT);
	}
}
