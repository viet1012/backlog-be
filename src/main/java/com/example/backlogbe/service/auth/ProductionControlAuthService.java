package com.example.backlogbe.service.auth;

import com.example.backlogbe.dto.auth.ProductionControlLoginRequest;
import com.example.backlogbe.dto.auth.ProductionControlLoginResponse;
import com.example.backlogbe.dto.auth.ProductionControlRegisterRequest;
import com.example.backlogbe.dto.auth.ProductionControlRegisterResponse;
import com.example.backlogbe.model.ProductionControlAccount;
import com.example.backlogbe.model.ProductionControlHrProfile;
import com.example.backlogbe.repository.auth.ProductionControlAuthRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ProductionControlAuthService {

	private static final String STATUS_ACTIVE = "ACTIVE";
	private static final String STATUS_LOCKED = "LOCKED";

	private static final String ROLE_ADMIN = "ADMIN";
	private static final String ROLE_USER = "USER";
	private static final String ROLE_PC = "PC";
	private static final String ROLE_PRO = "PRO";

	private final ProductionControlAuthRepository repository;
	private final PasswordEncoder passwordEncoder;

	// =========================================================
	// REGISTER
	// =========================================================

	@Transactional
	public ProductionControlRegisterResponse register(
			ProductionControlRegisterRequest request,
			String clientId
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

		ProductionControlHrProfile hr =
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
				repository.existsByEmployeeId(
						employeeId
				)
		) {

			throw new IllegalArgumentException(
					"Employee ID already has an account."
			);
		}


		// =====================================================
		// ROLE
		// =====================================================

		List<String> roles =
				determineRoles(hr);

		validateRolesExist(
				roles
		);


		// =====================================================
		// CREATE ACCOUNT
		// =====================================================

		Long accountId =
				repository.createAccount(
						employeeId,
						password,
						normalizeClientId(clientId)
				);


		// =====================================================
		// ASSIGN ROLE
		// =====================================================

		assignRoles(
				accountId,
				roles
		);


		return new ProductionControlRegisterResponse(
				accountId,
				employeeId,
				STATUS_ACTIVE,
				"Account created successfully."
		);
	}

	private String normalizeClientId(
			String clientId
	) {

		if (
				clientId == null
						|| clientId.isBlank()
		) {

			return "UNKNOWN";
		}

		return clientId.trim();
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
		// Account
		// -----------------------------------------------------

		ProductionControlAccount account =
				repository.findByEmployeeId(employeeId)
						.orElseThrow(this::invalidCredentials
						);

		// -----------------------------------------------------
		// Password
		// -----------------------------------------------------

//		if (!passwordEncoder.matches(
//				password,
//				account.passwordHash()
//		)) {
//			throw invalidCredentials();
//		}

		if (!password.equals(account.passwordHash())) {
			throw invalidCredentials();
		}
		// -----------------------------------------------------
		// Status
		// -----------------------------------------------------

		validateAccountStatus(account);

		// -----------------------------------------------------
		// Roles
		// -----------------------------------------------------

		List<String> roles =
				repository.findRolesByAccountId(
						account.id()
				);

		/*
		 * Trường hợp account cũ chưa có role
		 * thì không cần chặn login.
		 *
		 * Tạm coi là USER.
		 */
		if (roles.isEmpty()) {
			roles = List.of(ROLE_USER);
		}

		// -----------------------------------------------------
		// Last Login
		// -----------------------------------------------------

		repository.updateLastLoginAt(
				account.id()
		);

		// -----------------------------------------------------
		// Response
		// -----------------------------------------------------

		return new ProductionControlLoginResponse(
				account.id(),
				account.employeeId(),
				account.name(),
				account.fac(),
				account.dept(),
				account.section(),
				account.line(),
				account.group(),
				account.status(),
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

	// =========================================================
	// ASSIGN ROLE
	// =========================================================

	private void assignRoles(
			Long accountId,
			List<String> roles
	) {
		for (String role : roles) {
			repository.assignRole(
					accountId,
					role
			);
		}
	}

	private void validateRolesExist(
			List<String> roles
	) {
		for (String role : roles) {
			if (!repository.roleExists(role)) {
				throw new IllegalStateException(
						"Role does not exist: " + role
				);
			}
		}
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

	private void validateAccountStatus(
			ProductionControlAccount account
	) {
		String status =
				normalizeHrValue(account.status());

		if (STATUS_ACTIVE.equals(status)) {
			return;
		}

		if (STATUS_LOCKED.equals(status)) {
			throw new IllegalStateException(
					"Account has been locked."
			);
		}

		throw new IllegalStateException(
				"Account is not active."
		);
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