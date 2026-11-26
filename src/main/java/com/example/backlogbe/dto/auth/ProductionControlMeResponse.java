package com.example.backlogbe.dto.auth;

import java.util.List;
import java.util.Map;

// GET /api/production-control/auth/me
// name / fac / dept / section / line / group: vw_F2_ProductionControl_Employee (Grp -> group); không thấy NV -> null
// perms: Feature -> [ActionCode] đúng như view (có thể có "*" ở Feature hoặc Action = mọi giá trị)
public record ProductionControlMeResponse(
		String employeeId,
		String name,
		String fac,
		String dept,
		String section,
		String line,
		String group,
		List<String> roles,
		Map<String, List<String>> perms
) {
}
