package com.example.backlogbe.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

// =========================================================
// REQUEST TIMING FILTER (điều tra API chậm)
//
// Chỉ các path trong TIMED_PATHS: tạo requestId (8 ký tự hex) vào MDC cho cả request,
// cuối request log 1 dòng INFO: rid, method, path, status, durationMs (gồm cả serialize response).
// Không log query string / body. Không đổi request / response.
// =========================================================
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTimingFilter extends OncePerRequestFilter {

	private static final Set<String> TIMED_PATHS = Set.of(
			"/api/assistant/chat",
			"/api/cost-monitoring/detail"
	);

	@Override
	protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
		return !TIMED_PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()));
	}

	@Override
	protected void doFilterInternal(
			@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response,
			@NonNull FilterChain chain
	) throws ServletException, IOException {

		String rid = UUID.randomUUID().toString().substring(0, 8);
		long start = System.nanoTime();

		MDC.put(RequestTiming.MDC_KEY, rid);

		try {
			chain.doFilter(request, response);
		} finally {
			log.info(
					"Request timing: rid={} method={} path={} status={} durationMs={}",
					rid,
					request.getMethod(),
					request.getRequestURI(),
					response.getStatus(),
					RequestTiming.elapsedMs(start)
			);
			MDC.remove(RequestTiming.MDC_KEY);
		}
	}
}
