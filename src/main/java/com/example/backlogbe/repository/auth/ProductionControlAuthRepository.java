package com.example.backlogbe.repository.auth;

import com.example.backlogbe.model.ProductionControlAccount;
import com.example.backlogbe.model.ProductionControlHrProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ProductionControlAuthRepository {

	private final JdbcTemplate jdbcTemplate;

	// =========================================================
	// ACCOUNT EXISTS
	// =========================================================

	public boolean existsByEmployeeId(String employeeId) {

		String sql = """
				SELECT COUNT_BIG(*)
				FROM dbo.F2_ProductionControl_Account
				WHERE EmployeeId = ?
				""";

		Long count = jdbcTemplate.queryForObject(
				sql,
				Long.class,
				employeeId
		);

		return count != null && count > 0;
	}

	// =========================================================
	// HR
	// =========================================================

	public boolean existsInHr(String employeeId) {

		String sql = """
				SELECT CASE
				    WHEN EXISTS (
				        SELECT 1
				        FROM dbo.F2_HR_Data
				        WHERE LTRIM(RTRIM(CAST(Code AS VARCHAR(50)))) = ?
				    )
				    THEN 1
				    ELSE 0
				END
				""";

		Integer result = jdbcTemplate.queryForObject(
				sql,
				Integer.class,
				employeeId
		);

		return result != null && result == 1;
	}

	public Optional<ProductionControlHrProfile> findHrByEmployeeId(
			String employeeId
	) {

		String sql = """
				SELECT TOP 1
				    LTRIM(RTRIM(CAST(Code AS VARCHAR(50)))) AS EmployeeId,
				    Name,
				    Fac,
				    Dept,
				    Section,
				    Line,
				    [Group]
				FROM dbo.F2_HR_Data
				WHERE LTRIM(RTRIM(CAST(Code AS VARCHAR(50)))) = ?
				ORDER BY id DESC
				""";

		List<ProductionControlHrProfile> rows =
				jdbcTemplate.query(
						sql,
						(rs, rowNum) ->
								new ProductionControlHrProfile(
										rs.getString("EmployeeId"),
										rs.getString("Name"),
										rs.getString("Fac"),
										rs.getString("Dept"),
										rs.getString("Section"),
										rs.getString("Line"),
										rs.getString("Group")
								),
						employeeId
				);

		return rows.stream().findFirst();
	}

	// =========================================================
	// CREATE ACCOUNT
	// =========================================================

	public Long createAccount(
			String employeeId,
			String password,
			String clientId
	) {

		String sql = """
				INSERT INTO dbo.F2_ProductionControl_Account
				(
				    EmployeeId,
				    PasswordHash,
				    ClientId,
				    Status,
				    CreatedAt
				)
				OUTPUT INSERTED.Id
				VALUES
				(
				    ?,
				    ?,
				    ?,
				    'ACTIVE',
				    SYSDATETIME()
				)
				""";

		return jdbcTemplate.queryForObject(
				sql,
				Long.class,
				employeeId,
				password,
				clientId
		);
	}

	// =========================================================
	// ROLE
	// =========================================================

	public boolean roleExists(String roleCode) {

		String sql = """
				SELECT CASE
				    WHEN EXISTS (
				        SELECT 1
				        FROM dbo.F2_ProductionControl_Role
				        WHERE Code = ?
				    )
				    THEN 1
				    ELSE 0
				END
				""";

		Integer result = jdbcTemplate.queryForObject(
				sql,
				Integer.class,
				roleCode
		);

		return result != null && result == 1;
	}

	public void assignRole(
			Long accountId,
			String roleCode
	) {

		String sql = """
				INSERT INTO dbo.F2_ProductionControl_Account_Role
				(
				    AccountId,
				    RoleId,
				    AssignedBy,
				    AssignedAt
				)
				SELECT
				    ?,
				    r.Id,
				    'SYSTEM',
				    SYSDATETIME()
				FROM dbo.F2_ProductionControl_Role r
				WHERE r.Code = ?
				  AND NOT EXISTS (
				      SELECT 1
				      FROM dbo.F2_ProductionControl_Account_Role ar
				      WHERE ar.AccountId = ?
				        AND ar.RoleId = r.Id
				  )
				""";

		jdbcTemplate.update(
				sql,
				accountId,
				roleCode,
				accountId
		);
	}

	public List<String> findRolesByAccountId(Long accountId) {

		String sql = """
				SELECT
				    r.Code
				FROM dbo.F2_ProductionControl_Account_Role ar
				INNER JOIN dbo.F2_ProductionControl_Role r
				    ON r.Id = ar.RoleId
				WHERE ar.AccountId = ?
				ORDER BY r.Code
				""";

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> rs.getString("Code"),
				accountId
		);
	}

	// =========================================================
	// LOGIN
	// =========================================================

	public Optional<ProductionControlAccount> findByEmployeeId(
			String employeeId
	) {

		String sql = """
				SELECT
				    a.Id,
				    a.EmployeeId,
				
				    hr.Name,
				    hr.Fac,
				    hr.Dept,
				    hr.Section,
				    hr.Line,
				    hr.[Group],
				
				    a.PasswordHash,
				    a.Status
				
				FROM dbo.F2_ProductionControl_Account a
				
				LEFT JOIN dbo.F2_HR_Data hr
				    ON LTRIM(RTRIM(CAST(hr.Code AS VARCHAR(50))))
				       = a.EmployeeId
				
				WHERE a.EmployeeId = ?
				""";

		List<ProductionControlAccount> rows =
				jdbcTemplate.query(
						sql,
						(rs, rowNum) ->
								new ProductionControlAccount(
										rs.getLong("Id"),
										rs.getString("EmployeeId"),

										rs.getString("Name"),
										rs.getString("Fac"),
										rs.getString("Dept"),
										rs.getString("Section"),
										rs.getString("Line"),
										rs.getString("Group"),

										rs.getString("PasswordHash"),
										rs.getString("Status")
								),
						employeeId
				);

		return rows.stream().findFirst();
	}

	public void updateLastLoginAt(Long accountId) {

		String sql = """
				UPDATE dbo.F2_ProductionControl_Account
				SET LastLoginAt = SYSDATETIME()
				WHERE Id = ?
				""";

		jdbcTemplate.update(
				sql,
				accountId
		);
	}
}