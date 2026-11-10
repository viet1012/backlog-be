package com.example.backlogbe.assistant.core.api;

import com.example.backlogbe.assistant.core.api.dto.AssistantChatRequest;
import com.example.backlogbe.assistant.core.api.dto.AssistantChatResponse;
import com.example.backlogbe.assistant.core.api.dto.AssistantConfigResponse;
import com.example.backlogbe.assistant.core.api.dto.AssistantSuggestionsResponse;
import com.example.backlogbe.assistant.core.chat.ChatOrchestrator;
import com.example.backlogbe.assistant.core.config.AssistantProperties;
import com.example.backlogbe.assistant.core.screen.ParamSpec;
import com.example.backlogbe.assistant.core.screen.ScreenModule;
import com.example.backlogbe.assistant.core.screen.ScreenRegistry;
import com.example.backlogbe.assistant.core.suggestion.SuggestionEngine;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Cùng cơ chế với các API hiện có: không yêu cầu token.
// /chat: rate limit theo IP client (remoteAddr), 10 request / phút.
// /suggestions, /config: không rate limit (không gọi LLM).
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

	private final ChatOrchestrator chatOrchestrator;
	private final SuggestionEngine suggestionService;
	private final ScreenRegistry screenRegistry;
	private final AssistantRateLimiter rateLimiter;
	private final AssistantProperties assistantProperties;

	// GET /api/assistant/config -> { name: "..." }. Không gọi LLM, không rate limit.
	@GetMapping("/config")
	public ResponseEntity<AssistantConfigResponse> config() {
		return ResponseEntity.ok(new AssistantConfigResponse(assistantProperties.resolvedName()));
	}

	@PostMapping("/chat")
	public ResponseEntity<AssistantChatResponse> chat(
			@RequestBody AssistantChatRequest request,
			HttpServletRequest httpRequest
	) {

		// Khóa = mã nhân viên nếu có, không thì remoteAddr (không tin X-Forwarded-For – client tự đặt được)
		String employeeId = employeeId(httpRequest);
		String clientKey = AssistantRateLimiter.clientKey(employeeId, httpRequest.getRemoteAddr());
		rateLimiter.check(clientKey);

		return ResponseEntity.ok(
				chatOrchestrator.chat(request, employeeId, clientKey)
		);
	}

	// Mã nhân viên cho audit log: header X-Employee-Id (cùng cách các API khác trong repo; FE tự gửi, chưa xác thực).
	// Chỉ nhận [A-Za-z0-9_-]{1,32}; thiếu / sai dạng -> null. Không dùng để phân quyền.
	private static final java.util.regex.Pattern EMPLOYEE_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

	private static String employeeId(HttpServletRequest httpRequest) {

		String value = httpRequest.getHeader("X-Employee-Id");

		if (value == null) {
			return null;
		}

		String trimmed = value.trim();

		return EMPLOYEE_ID.matcher(trimmed).matches() ? trimmed : null;
	}

	// GET /api/assistant/suggestions?screenKey=<key>&<tham số màn hình>&lang=vi&count=4&exclude=...&exclude=...
	//   Tham số màn hình: đúng các tên trong ScreenModule.paramSpec() của screenKey (vd month, simuWeek);
	//   giá trị trống bỏ qua.
	// -> { greeting: "..." | null, suggestions: [{ text, kind: "dynamic" | "static" }] };
	//    screenKey không có file gợi ý -> suggestions []
	@GetMapping("/suggestions")
	public ResponseEntity<AssistantSuggestionsResponse> suggestions(
			@RequestParam String screenKey,
			@RequestParam(required = false) String lang,
			@RequestParam(required = false) Integer count,
			HttpServletRequest httpRequest
	) {

		// Đọc raw: @RequestParam List<String> sẽ tách 1 giá trị đơn theo dấu phẩy (câu hỏi hay có dấu phẩy)
		String[] excludeValues = httpRequest.getParameterValues("exclude");
		List<String> exclude = excludeValues != null ? Arrays.asList(excludeValues) : List.of();

		Map<String, String> params = new HashMap<>();
		ScreenModule module = screenRegistry.find(screenKey.trim());

		if (module != null) {
			for (ParamSpec spec : module.paramSpec()) {
				String value = httpRequest.getParameter(spec.name());
				if (value != null && !value.isBlank()) {
					params.put(spec.name(), value);
				}
			}
		}

		return ResponseEntity.ok(
				suggestionService.suggest(screenKey, params, lang, count, exclude)
		);
	}
}
