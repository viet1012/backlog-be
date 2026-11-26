package com.example.backlogbe.exception;

import com.example.backlogbe.controller.Pdf2cadController;
import com.example.backlogbe.service.pdf2cad.Pdf2cadAuditLogger;
import com.example.backlogbe.service.pdf2cad.Pdf2cadProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

// =========================================================
// PDF2CAD ERROR RESPONSE
//
// Chỉ áp dụng cho /api/pdf2cad (Pdf2cadController), ưu tiên hơn GlobalExceptionHandler.
// Body: { "detail": "..." } – cùng dạng lỗi của server pdf2cad để FE xử lý 1 kiểu.
//
// - 401 / 403 do ProductionControlAuthFilter / RequirePermInterceptor trả (AuthErrorWriter, body {status, message}),
//   không qua handler này; nhánh 401 / 403 bên dưới chỉ còn cho ResponseStatusException nếu có.
// - Job không có / của người khác -> 404
// - Upload vượt pdf2cad.max-upload-mb -> 413
// - Không kết nối được / quá thời gian chờ pdf2cad -> 502 / 504
// - Lỗi khác -> 500, chi tiết chỉ ghi log
// Response đã gửi dở (đang stream file thì pdf2cad / trình duyệt ngắt) -> không ghi thêm gì.
// =========================================================
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = Pdf2cadController.class)
@RequiredArgsConstructor
public class Pdf2cadExceptionHandler {

	private final Pdf2cadAuditLogger audit;
	private final Pdf2cadProxyService proxy;

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handleStatus(
			ResponseStatusException ex,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		HttpStatusCode status = ex.getStatusCode();

		if (status.value() == 401 || status.value() == 403) {
			audit.write(null, request.getRemoteAddr(), "denied", null,
					request.getMethod() + " " + request.getRequestURI(), status.value(), System.nanoTime());
		}

		return body(response, status, ex.getReason() != null ? ex.getReason() : "Request failed");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, String>> handleTypeMismatch(
			MethodArgumentTypeMismatchException ex,
			HttpServletResponse response
	) {
		return body(response, HttpStatus.BAD_REQUEST, "Invalid value for " + ex.getName());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleNotReadable(HttpServletResponse response) {
		return body(response, HttpStatus.BAD_REQUEST, "Invalid request body");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<Map<String, String>> handleMediaType(HttpServletResponse response) {
		return body(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type");
	}

	@ExceptionHandler(ResourceAccessException.class)
	public ResponseEntity<Map<String, String>> handleUnreachable(
			ResourceAccessException ex,
			HttpServletResponse response
	) {
		if (hasCause(ex, Pdf2cadProxyService.UploadTooLargeException.class)) {
			return body(response, HttpStatus.PAYLOAD_TOO_LARGE, proxy.tooLargeMessage());
		}

		boolean timeout = hasCause(ex, java.net.http.HttpTimeoutException.class);
		log.warn("pdf2cad: {}: {}", timeout ? "timeout" : "cannot reach server", ex.getMessage());

		return timeout
				? body(response, HttpStatus.GATEWAY_TIMEOUT, "Server pdf2cad trả lời quá lâu, hãy thử lại")
				: body(response, HttpStatus.BAD_GATEWAY, "Không kết nối được server pdf2cad");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex, HttpServletResponse response) {
		log.error("pdf2cad: unexpected proxy error", ex);
		return body(response, HttpStatus.INTERNAL_SERVER_ERROR,
				"An unexpected error occurred. Please try again or contact IT.");
	}

	private static ResponseEntity<Map<String, String>> body(
			HttpServletResponse response,
			HttpStatusCode status,
			String detail
	) {
		// đang stream file mà bị ngắt: header đã gửi, không ghi thêm được
		if (response.isCommitted()) {
			return null;
		}
		return ResponseEntity.status(status).body(Map.of("detail", detail));
	}

	private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (type.isInstance(t)) return true;
		}
		return false;
	}
}
