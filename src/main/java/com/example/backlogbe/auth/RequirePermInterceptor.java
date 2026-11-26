package com.example.backlogbe.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

// =========================================================
// KIỂM QUYỀN @RequirePerm (đăng ký cho /api/**, trừ AuthPaths.PUBLIC_PATHS – xem AuthWebConfig)
//
// Endpoint không có @RequirePerm: chỉ cần đã đăng nhập (filter lo).
// auth.enforce=true : chưa đăng nhập -> 401, thiếu quyền -> 403.
// auth.enforce=false: không chặn; log WARN "would-deny" (path, empCode, feature, action, reason) khi
//                     không có session hợp lệ hoặc thiếu quyền. Lỗi đọc quyền -> log, cho qua.
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class RequirePermInterceptor implements HandlerInterceptor {

	private static final String NONE = "-";

	private final ProductionControlPermissionService permissionService;
	private final AuthProperties authProperties;
	private final AuthErrorWriter errorWriter;

	@Override
	public boolean preHandle(
			@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response,
			@NonNull Object handler
	) throws Exception {

		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}

		RequirePerm perm = method.getMethodAnnotation(RequirePerm.class);

		if (perm == null) {
			perm = method.getBeanType().getAnnotation(RequirePerm.class);
		}

		CurrentUser user = CurrentUser.of(request).orElse(null);

		return authProperties.enforce()
				? enforce(request, response, user, perm)
				: observe(request, user, perm);
	}

	// ---- auth.enforce=true ----
	private boolean enforce(
			HttpServletRequest request,
			HttpServletResponse response,
			CurrentUser user,
			RequirePerm perm
	) throws Exception {

		// Filter đã chặn; phòng trường hợp filter không chạy
		if (user == null || !user.tokenAuthenticated()) {
			errorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Login is required.");
			return false;
		}

		if (perm != null && !permissionService.has(user.empCode(), perm.feature(), perm.action())) {
			log.warn("Auth deny: path={} empCode={} feature={} action={}",
					AuthPaths.path(request), user.empCode(), perm.feature(), perm.action());
			errorWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
					"Bạn không có quyền " + perm.feature() + "/" + perm.action() + ". Liên hệ IT nếu cần cấp quyền.");
			return false;
		}

		return true;
	}

	// ---- auth.enforce=false ----
	private boolean observe(HttpServletRequest request, CurrentUser user, RequirePerm perm) {

		String feature = perm != null ? perm.feature() : NONE;
		String action = perm != null ? perm.action() : NONE;
		String empCode = user != null ? user.empCode() : NONE;

		String reason = null;

		if (user == null || !user.tokenAuthenticated()) {
			Object problem = request.getAttribute(ProductionControlAuthFilter.ATTR_AUTH_PROBLEM);
			reason = problem != null ? problem.toString() : ProductionControlAuthFilter.PROBLEM_MISSING_TOKEN;
		}

		if (perm != null && user != null) {
			try {
				if (!permissionService.has(user.empCode(), perm.feature(), perm.action())) {
					reason = reason == null ? "no-permission" : reason + "+no-permission";
				}
			} catch (RuntimeException e) {
				log.warn("Auth: permission lookup failed {}", e.getClass().getSimpleName());
				reason = reason == null ? "permission-error" : reason + "+permission-error";
			}
		}

		if (reason != null) {
			log.warn("Auth would-deny: reason={} path={} empCode={} feature={} action={}",
					reason, AuthPaths.path(request), empCode, feature, action);
		}

		return true;
	}
}
