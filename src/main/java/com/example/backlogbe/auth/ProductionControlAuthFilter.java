package com.example.backlogbe.auth;

import com.example.backlogbe.repository.auth.ProductionControlSessionRepository.SessionRow;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

// =========================================================
// XÁC THỰC (không dùng Spring Security)
//
// Authorization: Bearer <token> -> SHA-256 -> F2_ProductionControl_Session (RevokedAt IS NULL AND ExpiresAt > now)
// -> CurrentUser (request attribute). Bỏ qua: AuthPaths.PUBLIC_PATHS, OPTIONS, đường dẫn ngoài /api/.
//
// auth.enforce=true : thiếu / sai token -> 401. Không đọc X-Employee-Id.
// auth.enforce=false: không chặn. Không có session hợp lệ -> tạm lấy mã NV từ X-Employee-Id (chưa xác thực);
//                     log "would-deny" do RequirePermInterceptor ghi (biết feature / action).
// Chạy sau CorsFilter (CorsConfig) để response 401 vẫn có header CORS.
// =========================================================
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class ProductionControlAuthFilter extends OncePerRequestFilter {

	// Lý do không có session (đọc ở interceptor để log would-deny)
	static final String ATTR_AUTH_PROBLEM = ProductionControlAuthFilter.class.getName() + ".problem";

	static final String PROBLEM_MISSING_TOKEN = "missing-token";
	static final String PROBLEM_INVALID_TOKEN = "invalid-token";
	static final String PROBLEM_SESSION_ERROR = "session-error";

	private static final String LEGACY_HEADER = "X-Employee-Id";
	private static final Pattern EMPLOYEE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

	private final ProductionControlSessionService sessionService;
	private final AuthProperties authProperties;
	private final AuthErrorWriter errorWriter;

	// Mọi request OPTIONS (preflight CORS) đi thẳng, không kiểm token
	@Override
	protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
		return "OPTIONS".equalsIgnoreCase(request.getMethod())
				|| AuthPaths.isPublic(request);
	}

	@Override
	protected void doFilterInternal(
			@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response,
			@NonNull FilterChain chain
	) throws ServletException, IOException {

		String token = AuthPaths.bearerToken(request);
		String problem = token == null ? PROBLEM_MISSING_TOKEN : null;

		if (token != null) {
			try {
				SessionRow session = sessionService.resolve(token).orElse(null);

				if (session != null && session.empCode() != null && !session.empCode().isBlank()) {
					CurrentUser.set(request, new CurrentUser(session.empCode().trim(), true));
				} else {
					problem = PROBLEM_INVALID_TOKEN;
				}
			} catch (DataAccessException e) {
				log.error("Auth: session lookup failed {}", e.getClass().getSimpleName(), e);
				problem = PROBLEM_SESSION_ERROR;
			}
		}

		if (problem != null) {

			if (authProperties.enforce()) {

				if (PROBLEM_SESSION_ERROR.equals(problem)) {
					errorWriter.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
							"Không kiểm tra được phiên đăng nhập, vui lòng thử lại.");
				} else {
					errorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
							PROBLEM_MISSING_TOKEN.equals(problem)
									? "Login is required."
									: "Session expired or invalid. Please log in again.");
				}
				return;
			}

			// Giai đoạn chuyển tiếp: mã NV do client gửi (chưa xác thực)
			String legacy = legacyEmployeeId(request);

			if (legacy != null) {
				CurrentUser.set(request, new CurrentUser(legacy, false));
			}

			request.setAttribute(ATTR_AUTH_PROBLEM, problem);
		}

		chain.doFilter(request, response);
	}

	private static String legacyEmployeeId(HttpServletRequest request) {

		String value = request.getHeader(LEGACY_HEADER);

		if (value == null) {
			return null;
		}

		String trimmed = value.trim();

		return EMPLOYEE_ID.matcher(trimmed).matches() ? trimmed.toUpperCase(Locale.ROOT) : null;
	}
}
