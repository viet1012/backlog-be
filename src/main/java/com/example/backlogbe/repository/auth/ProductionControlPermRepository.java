package com.example.backlogbe.repository.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

// =========================================================
// dbo.vw_F2_ProductionControl_EmpPerm (EmpCode, AppCode, RoleCode, Feature, ActionCode)
// Feature / ActionCode = '*' nghĩa là mọi giá trị.
// =========================================================
@Repository
@RequiredArgsConstructor
public class ProductionControlPermRepository {

	private final JdbcTemplate jdbcTemplate;

	public record PermRow(String roleCode, String feature, String actionCode) {
	}

	// Mọi dòng quyền của 1 NV trong 1 app (đủ cho cả kiểm tra quyền lẫn /me).
	// Kiểm tra 1 quyền trên kết quả này tương đương:
	//   SELECT TOP 1 1 FROM vw_F2_ProductionControl_EmpPerm
	//   WHERE EmpCode=? AND AppCode='PCW' AND Feature IN (?, '*') AND ActionCode IN (?, '*')
	public List<PermRow> findByEmp(String empCode, String appCode) {

		String sql = """
				SELECT DISTINCT RoleCode, Feature, ActionCode
				FROM dbo.vw_F2_ProductionControl_EmpPerm
				WHERE EmpCode = CAST(? AS VARCHAR(50))
				  AND AppCode = ?
				ORDER BY RoleCode, Feature, ActionCode
				""";

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new PermRow(
						rs.getString("RoleCode"),
						rs.getString("Feature"),
						rs.getString("ActionCode")
				),
				empCode, appCode
		);
	}
}
