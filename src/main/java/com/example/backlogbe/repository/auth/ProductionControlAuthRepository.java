package com.example.backlogbe.repository.auth;

import com.example.backlogbe.model.PatrolAccount;
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
	// NHÂN VIÊN ĐANG LÀM (vw_F2_ProductionControl_Employee, IsActive = 1) – register kiểm tra MSNV
	// =========================================================

	public boolean existsActiveEmployee(String empCode) {

		String sql = """
				SELECT CASE
				    WHEN EXISTS (
				        SELECT 1
				        FROM dbo.vw_F2_ProductionControl_Employee
				        WHERE EmpCode = CAST(? AS VARCHAR(50))
				          AND IsActive = 1
				    )
				    THEN 1
				    ELSE 0
				END
				""";

		Integer result = jdbcTemplate.queryForObject(
				sql,
				Integer.class,
				empCode
		);

		return result != null && result == 1;
	}

	// =========================================================
	// TÊN + TỔ CHỨC (vw_F2_ProductionControl_Employee, tra theo EmpCode; cột Grp -> group)
	// Dùng cho login response và /me.
	// =========================================================

	public record EmployeeOrg(
			String name,
			String fac,
			String dept,
			String section,
			String line,
			String group
	) {
	}

	public Optional<EmployeeOrg> findEmployeeOrg(String empCode) {

		String sql = """
				SELECT TOP 1 Name, Fac, Dept, Section, Line, Grp
				FROM dbo.vw_F2_ProductionControl_Employee
				WHERE EmpCode = CAST(? AS VARCHAR(50))
				""";

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new EmployeeOrg(
						rs.getString("Name"),
						rs.getString("Fac"),
						rs.getString("Dept"),
						rs.getString("Section"),
						rs.getString("Line"),
						rs.getString("Grp")
				),
				empCode
		).stream().findFirst();
	}

	// =========================================================
	// S-PATROL ACCOUNT (bảng user duy nhất)
	// =========================================================

	public Optional<PatrolAccount> findPatrolAccount(
			String employeeId
	) {

		String sql = """
				SELECT TOP 1
				    Account,
				    Pass
				FROM dbo.HSE_Patrol_Account
				WHERE UPPER(LTRIM(RTRIM(Account))) = ?
				""";

		List<PatrolAccount> rows =
				jdbcTemplate.query(
						sql,
						(rs, rowNum) ->
								new PatrolAccount(
										rs.getString("Account"),
										rs.getString("Pass")
								),
						employeeId
				);

		return rows.stream().findFirst();
	}

	public boolean existsPatrolAccount(String employeeId) {

		String sql = """
				SELECT CASE
				    WHEN EXISTS (
				        SELECT 1
				        FROM dbo.HSE_Patrol_Account
				        WHERE UPPER(LTRIM(RTRIM(Account))) = ?
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

	public void createPatrolAccount(
			String employeeId,
			String password
	) {

		String sql = """
				INSERT INTO dbo.HSE_Patrol_Account
				(
				    Account,
				    Pass,
				    NewDT,
				    UpdDT
				)
				VALUES
				(
				    ?,
				    ?,
				    SYSDATETIME(),
				    SYSDATETIME()
				)
				""";

		jdbcTemplate.update(
				sql,
				employeeId,
				password
		);
	}

	public void updatePatrolLastLogin(String employeeId) {

		String sql = """
				UPDATE dbo.HSE_Patrol_Account
				SET Last_Login = SYSDATETIME()
				WHERE UPPER(LTRIM(RTRIM(Account))) = ?
				""";

		jdbcTemplate.update(
				sql,
				employeeId
		);
	}
}