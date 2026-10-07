package com.example.backlogbe.service.auth;

import com.example.backlogbe.dto.auth.ProductionControlLoginRequest;
import com.example.backlogbe.dto.auth.ProductionControlLoginResponse;
import com.example.backlogbe.dto.auth.ProductionControlRegisterRequest;
import com.example.backlogbe.dto.auth.ProductionControlRegisterResponse;
import com.example.backlogbe.model.PatrolAccount;
import com.example.backlogbe.model.ProductionControlHrProfile;
import com.example.backlogbe.repository.auth.ProductionControlAuthRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ProductionControlAuthService {

	private static final String STATUS_ACTIVE = "ACTIVE";

	private static final String ROLE_ADMIN = "ADMIN";
	private static final String ROLE_USER = "USER";
	private static final String ROLE_PC = "PC";
	private static final String ROLE_PRO = "PRO";

	private final ProductionControlAuthRepository repository;

	// =========================================================
	// REGISTER
	// =========================================================

	@Transactional
	public ProductionControlRegisterResponse register(
			ProductionControlRegisterRequest request
	) {

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
		// HR
		// =====================================================

		repository.findHrByEmployeeId(
						employeeId
				)
				.orElseThrow(() ->
						new IllegalArgumentException(
								"Employee ID does not exist in company HR data."
						)
				);


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
			ProductionControlLoginRequest request
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
		// Password (HSE_Patrol_Account)
		// -----------------------------------------------------

		PatrolAccount patrolAccount =
				repository.findPatrolAccount(employeeId)
						.orElseThrow(this::invalidCredentials);

		if (!password.equals(patrolAccount.pass())) {
			throw invalidCredentials();
		}

		// -----------------------------------------------------
		// HR + Roles (tính lại mỗi lần login, không lưu DB)
		// -----------------------------------------------------

		/*
		 * Không có HR thì vẫn cho login,
		 * tạm coi là USER.
		 */
		ProductionControlHrProfile hr =
				repository.findHrByEmployeeId(employeeId)
						.orElse(null);

		List<String> roles =
				hr != null
						? determineRoles(hr)
						: List.of(ROLE_USER);

		// -----------------------------------------------------
		// Last Login
		// -----------------------------------------------------

		repository.updatePatrolLastLogin(
				employeeId
		);

		// -----------------------------------------------------
		// Response
		// -----------------------------------------------------

		return new ProductionControlLoginResponse(
				null,
				employeeId,
				hr != null ? hr.name() : null,
				hr != null ? hr.fac() : null,
				hr != null ? hr.dept() : null,
				hr != null ? hr.section() : null,
				hr != null ? hr.line() : null,
				hr != null ? hr.group() : null,
				STATUS_ACTIVE,
				roles
		);
	}

	// =========================================================
	// DETERMINE ROLE
	// =========================================================

	private List<String> determineRoles(
			ProductionControlHrProfile hr
	) {
		String dept =
				normalizeHrValue(hr.dept());

		String section =
				normalizeHrValue(hr.section());

		String line =
				normalizeHrValue(hr.line());

		String group =
				normalizeHrValue(hr.group());

		// IT => ADMIN
		if (
				equalsRole(dept, "K COMMON")
						&& (
						containsRole(section, "PE IT")
								|| containsRole(line, "PE IT")
								|| containsRole(group, "PE IT")
				)
		) {
			return List.of(ROLE_ADMIN);
		}

		List<String> roles = new ArrayList<>();

		// Guide PC / Mold PC / PC...
		if (
				containsRole(section, ROLE_PC)
						|| containsRole(line, ROLE_PC)
						|| containsRole(group, ROLE_PC)
		) {
			addRole(roles, ROLE_PC);
		}

		// KP Pro / KM Pro / Guide Pro...
		if (
				containsRole(section, ROLE_PRO)
						|| containsRole(line, ROLE_PRO)
						|| containsRole(group, ROLE_PRO)
		) {
			addRole(roles, ROLE_PRO);
		}

		// Không map được => USER
		if (roles.isEmpty()) {
			addRole(roles, ROLE_USER);
		}

		return roles;
	}


	private void addRole(
			List<String> roles,
			String role
	) {
		if (!roles.contains(role)) {
			roles.add(role);
		}
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

	// =========================================================
	// ROLE MATCH
	// =========================================================

	private boolean equalsRole(
			String value,
			String role
	) {
		return value.equals(role);
	}

	private boolean containsRole(
			String value,
			String role
	) {
		return value.contains(role);
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

	private String normalizeHrValue(
			String value
	) {
		if (value == null) {
			return "";
		}

		return value
				.trim()
				.toUpperCase(Locale.ROOT);
	}
}