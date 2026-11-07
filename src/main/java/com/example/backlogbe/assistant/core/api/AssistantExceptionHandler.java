package com.example.backlogbe.assistant.core.api;

import com.example.backlogbe.assistant.core.eval.AssistantEvalController;
import com.example.backlogbe.assistant.core.llm.LlmBusyException;
import com.example.backlogbe.assistant.core.llm.LlmEmptyException;
import com.example.backlogbe.assistant.core.llm.LlmTruncatedException;
import com.example.backlogbe.assistant.core.llm.LlmUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

// =========================================================
// ASSISTANT ERROR RESPONSE
//
// Body: { "status": <http>, "code": "<CODE>", "message": "..." }
//
// - Validate / body sai                    -> 400 BAD_REQUEST
// - Vượt rate limit theo IP                -> 429 RATE_LIMITED (+ header Retry-After)
// - LLM quá tải (vượt maxConcurrent)       -> 503 LLM_BUSY
// - Content rỗng, finish_reason=length     -> 503 LLM_TRUNCATED
// - Content rỗng, lý do khác               -> 503 LLM_EMPTY
// - LLM lỗi HTTP / timeout / không kết nối -> 503 LLM_ERROR
// - Lỗi khác (vd SQL khi lấy context)      -> 500 INTERNAL
//
// Không log nội dung câu hỏi / câu trả lời.
// =========================================================
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AssistantController.class, AssistantEvalController.class})
public class AssistantExceptionHandler {

	static final String CODE_BAD_REQUEST = "BAD_REQUEST";
	static final String CODE_RATE_LIMITED = "RATE_LIMITED";
	static final String CODE_LLM_BUSY = "LLM_BUSY";
	static final String CODE_LLM_ERROR = "LLM_ERROR";
	static final String CODE_LLM_TRUNCATED = "LLM_TRUNCATED";
	static final String CODE_LLM_EMPTY = "LLM_EMPTY";
	static final String CODE_INTERNAL = "INTERNAL";

	private static final String RATE_LIMITED_MESSAGE =
			"Too many requests. Please wait a moment and try again.";

	private static final String BUSY_MESSAGE =
			"The assistant is busy right now. Please try again in a moment.";

	private static final String UNAVAILABLE_MESSAGE =
			"The assistant is temporarily unavailable. Please try again later.";

	private static final String INTERNAL_ERROR_MESSAGE =
			"An unexpected error occurred. Please try again or contact IT.";

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
		return body(
				HttpStatus.BAD_REQUEST,
				CODE_BAD_REQUEST,
				ex.getMessage() != null ? ex.getMessage() : "Invalid request"
		);
	}

	// Thiếu query param bắt buộc (vd screenKey) hoặc sai kiểu (vd count không phải số)
	@ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<Map<String, Object>> handleBadParam(Exception ex) {

		String name = ex instanceof MissingServletRequestParameterException missing
				? missing.getParameterName()
				: ((MethodArgumentTypeMismatchException) ex).getName();

		return body(HttpStatus.BAD_REQUEST, CODE_BAD_REQUEST, "Invalid or missing parameter: " + name);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
		return body(HttpStatus.BAD_REQUEST, CODE_BAD_REQUEST, "Invalid request body.");
	}

	@ExceptionHandler(RateLimitedException.class)
	public ResponseEntity<Map<String, Object>> handleRateLimited(RateLimitedException ex) {

		log.warn("Assistant: rate limited");

		return ResponseEntity
				.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
				.body(payload(HttpStatus.TOO_MANY_REQUESTS, CODE_RATE_LIMITED, RATE_LIMITED_MESSAGE));
	}

	@ExceptionHandler(LlmBusyException.class)
	public ResponseEntity<Map<String, Object>> handleBusy(LlmBusyException ex) {
		log.warn("Assistant: LLM busy (concurrency limit reached)");
		return body(HttpStatus.SERVICE_UNAVAILABLE, CODE_LLM_BUSY, BUSY_MESSAGE);
	}

	@ExceptionHandler(LlmTruncatedException.class)
	public ResponseEntity<Map<String, Object>> handleTruncated(LlmTruncatedException ex) {
		log.warn("Assistant: {}", ex.getMessage());
		return body(HttpStatus.SERVICE_UNAVAILABLE, CODE_LLM_TRUNCATED, UNAVAILABLE_MESSAGE);
	}

	@ExceptionHandler(LlmEmptyException.class)
	public ResponseEntity<Map<String, Object>> handleEmpty(LlmEmptyException ex) {
		log.warn("Assistant: {}", ex.getMessage());
		return body(HttpStatus.SERVICE_UNAVAILABLE, CODE_LLM_EMPTY, UNAVAILABLE_MESSAGE);
	}

	@ExceptionHandler(LlmUnavailableException.class)
	public ResponseEntity<Map<String, Object>> handleUnavailable(LlmUnavailableException ex) {
		// Message chỉ có status / loại lỗi; không in stacktrace của RestClient (có thể kèm body)
		log.warn("Assistant: LLM unavailable - {}", ex.getMessage());
		return body(HttpStatus.SERVICE_UNAVAILABLE, CODE_LLM_ERROR, UNAVAILABLE_MESSAGE);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
		log.error("Assistant: unexpected error", ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, CODE_INTERNAL, INTERNAL_ERROR_MESSAGE);
	}

	private ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String code, String message) {
		return ResponseEntity.status(status).body(payload(status, code, message));
	}

	private static Map<String, Object> payload(HttpStatusCode status, String code, String message) {

		Map<String, Object> body = new LinkedHashMap<>();

		body.put("status", status.value());
		body.put("code", code);
		body.put("message", message);

		return body;
	}
}
