package com.example.backlogbe.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

// Ghi lỗi 401 / 403 / 503 từ filter / interceptor: { "status": <code>, "message": "..." }
@Component
@RequiredArgsConstructor
class AuthErrorWriter {

	private final ObjectMapper objectMapper;

	void write(HttpServletResponse response, int status, String message) throws IOException {

		if (response.isCommitted()) {
			return;
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status);
		body.put("message", message);

		response.setStatus(status);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), body);
	}
}
