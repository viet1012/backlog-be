package com.example.backlogbe.exception;

import com.example.backlogbe.controller.ProductionControlAuthController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

// =========================================================
// Lỗi 401 / 403 / 429 của /api/production-control/auth: { "status": <code>, "message": "..." }
// IllegalArgumentException (sai mật khẩu, thiếu field…) vẫn do GlobalExceptionHandler -> 400 { "message" }.
// =========================================================
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ProductionControlAuthController.class)
public class ProductionControlAuthExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", ex.getStatusCode().value());
		body.put("message", ex.getReason() != null ? ex.getReason() : "Request failed");

		return ResponseEntity.status(ex.getStatusCode()).body(body);
	}
}
