package com.example.backlogbe.dto.auth;

import java.util.List;

// roles: DISTINCT RoleCode trong vw_F2_ProductionControl_EmpPerm (AppCode PCW)
// token: gửi lại ở header "Authorization: Bearer <token>"; expiresAt: ISO-8601 UTC (gia hạn trượt khi dùng)
public record ProductionControlLoginResponse(
		Long accountId,
		String employeeId,
		String name,
		String fac,
		String dept,
		String section,
		String line,
		String group,
		String status,
		List<String> roles,
		String token,
		String expiresAt
) {
}
