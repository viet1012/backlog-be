package com.example.backlogbe.exception;

import com.example.backlogbe.controller.FacConfirmController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

// =========================================================
// FAC CONFIRM ERROR RESPONSE
//
// Chỉ áp dụng cho /api/fac-confirm (FacConfirmController),
// ưu tiên hơn GlobalExceptionHandler.
//
// Body: { "status": <code>, "message": "..." }
//
// - Lỗi validate / quyền sửa (IllegalArgumentException) -> 400 + message.
// - Lỗi request sai định dạng -> 400 + message chung, không lộ chi tiết parser.
// - Lỗi khác (SQL, IllegalStateException nội bộ...) -> 500 + message chung,
//   chi tiết chỉ ghi log.
// =========================================================
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FacConfirmController.class)
public class FacConfirmExceptionHandler {

	private static final String INTERNAL_ERROR_MESSAGE =
			"An unexpected error occurred. Please try again or contact IT.";

	// =====================================================
	// 400 - VALIDATE / QUYỀN SỬA
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

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<Map<String, Object>> handleMissingParameter(
			MissingServletRequestParameterException ex
	) {
		return body(
				HttpStatus.BAD_REQUEST,
				ex.getParameterName() + " is required"
		);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, Object>> handleTypeMismatch(
			MethodArgumentTypeMismatchException ex
	) {
		return body(
				HttpStatus.BAD_REQUEST,
				"Invalid value for " + ex.getName()
		);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleNotReadable(
			HttpMessageNotReadableException ex
	) {
		log.warn("Fac Confirm: unreadable request body: {}", ex.getMessage());

		return body(
				HttpStatus.BAD_REQUEST,
				"Invalid request body"
		);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<Map<String, Object>> handleMediaType(
			HttpMediaTypeNotSupportedException ex
	) {
		return body(
				HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				"Unsupported content type"
		);
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> handleResponseStatus(
			ResponseStatusException ex
	) {
		HttpStatusCode status =
				ex.getStatusCode();

		if (status.is5xxServerError()) {
			return internalError(ex);
		}

		return body(
				status,
				ex.getReason() != null
						? ex.getReason()
						: "Request failed"
		);
	}

	// =====================================================
	// 500 - KHÔNG LỘ CHI TIẾT
	// =====================================================

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(
			Exception ex
	) {
		return internalError(ex);
	}

	private ResponseEntity<Map<String, Object>> internalError(
			Exception ex
	) {
		log.error("Fac Confirm: unexpected error", ex);

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
