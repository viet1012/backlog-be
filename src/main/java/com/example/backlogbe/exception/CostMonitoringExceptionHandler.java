package com.example.backlogbe.exception;

import com.example.backlogbe.controller.CostMonitoringController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

// =========================================================
// COST MONITORING ERROR RESPONSE
//
// Chỉ áp dụng cho /api/cost-monitoring (CostMonitoringController),
// ưu tiên hơn GlobalExceptionHandler. Cùng body với Machine Locator.
//
// Body: { "status": <code>, "message": "..." }
//
// - Validate (IllegalArgumentException)         -> 400
// - Lỗi khác (vd lỗi SQL từ SP)                 -> 500, chi tiết chỉ ghi log
// =========================================================
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CostMonitoringController.class)
public class CostMonitoringExceptionHandler {

	private static final String INTERNAL_ERROR_MESSAGE =
			"An unexpected error occurred. Please try again or contact IT.";

	// =====================================================
	// 400 - VALIDATE
	// =====================================================

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleBadRequest(
			IllegalArgumentException ex
	) {
		return body(
				HttpStatus.BAD_REQUEST,
				ex.getMessage() != null
						? ex.getMessage()
						: "Invalid request"
		);
	}

	// Sai kiểu tham số (vd page=abc) -> 400 thay vì 500
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, Object>> handleTypeMismatch(
			MethodArgumentTypeMismatchException ex
	) {
		return body(
				HttpStatus.BAD_REQUEST,
				ex.getName() + " has an invalid value."
		);
	}

	// =====================================================
	// 500 - KHÔNG LỘ CHI TIẾT
	// =====================================================

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(
			Exception ex
	) {
		log.error("Cost Monitoring: unexpected error", ex);

		return body(
				HttpStatus.INTERNAL_SERVER_ERROR,
				INTERNAL_ERROR_MESSAGE
		);
	}

	private ResponseEntity<Map<String, Object>> body(
			HttpStatusCode status,
			String message
	) {
		Map<String, Object> body = new LinkedHashMap<>();

		body.put("status", status.value());
		body.put("message", message);

		return ResponseEntity
				.status(status)
				.body(body);
	}
}
