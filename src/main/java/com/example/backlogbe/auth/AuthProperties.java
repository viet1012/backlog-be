package com.example.backlogbe.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// =========================================================
// CẤU HÌNH XÁC THỰC / PHÂN QUYỀN
//
// auth.enforce (mặc định false):
//   false: không chặn; thiếu token / thiếu quyền chỉ log WARN "would-deny".
//          Không có token -> tạm lấy mã NV từ X-Employee-Id (hoặc employeeId trong body ở API cũ có field này).
//   true : thiếu / sai token -> 401, thiếu quyền -> 403; không đọc mã NV do client gửi.
// auth.register.enabled (mặc định false): false -> POST /register trả 403.
// =========================================================
@Component
public class AuthProperties {

	private final boolean enforce;
	private final boolean registerEnabled;

	public AuthProperties(
			@Value("${auth.enforce:false}") boolean enforce,
			@Value("${auth.register.enabled:false}") boolean registerEnabled
	) {
		this.enforce = enforce;
		this.registerEnabled = registerEnabled;
	}

	public boolean enforce() {
		return enforce;
	}

	public boolean registerEnabled() {
		return registerEnabled;
	}
}
