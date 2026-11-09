package com.example.backlogbe.assistant.core.audit.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

// =========================================================
// Lỗi của /api/admin/assistant-logs. Body: { "status": <http>, "message": "..." }
// - Tham số sai (IllegalArgumentException, sai kiểu)  -> 400
// - Thiếu X-Employee-Id / không phải ADMIN / không thấy -> 401 / 403 / 404
// - Lỗi khác (vd đọc file)                             -> 500, chi tiết chỉ ghi log (không kèm nội dung log)
// =========================================================
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AssistantLogAdminController.class)
public class AssistantLogAdminExceptionHandler {

	private static final String INTERNAL_ERROR_MESSAGE =
			"An unexpected error occurred. Please try again or contact IT.";

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
		return body(HttpStatus.BAD_REQUEST, ex.getMessage() != null ? ex.getMessage() : "Invalid request");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return body(HttpStatus.BAD_REQUEST, "Invalid value for " + ex.getName());
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {

		if (ex.getStatusCode().is5xxServerError()) {
			return internalError(ex);
		}

		return body(ex.getStatusCode(), ex.getReason() != null ? ex.getReason() : "Request failed");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
		return internalError(ex);
	}

	private ResponseEntity<Map<String, Object>> internalError(Exception ex) {
		log.error("Assistant audit admin: unexpected error {}", ex.getClass().getSimpleName(), ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MESSAGE);
	}

	private static ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String message) {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status.value());
		body.put("message", message);

		return ResponseEntity.status(status).body(body);
	}
}
