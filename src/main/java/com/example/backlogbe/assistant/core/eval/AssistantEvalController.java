package com.example.backlogbe.assistant.core.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

// =========================================================
// POST /api/assistant/eval?screenKey=<key>   (chỉ profile dev; screenKey bắt buộc, thiếu -> 400 BAD_REQUEST)
//
// Chạy bộ câu hỏi trong resources/assistant/screens/{screenKey}/eval.json và trả bảng pass/fail
// kèm câu trả lời. Profile khác: bean không được tạo -> endpoint không tồn tại (404).
// =========================================================
@Profile("dev")
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantEvalController {

	private static final Pattern SCREEN_KEY = Pattern.compile("^[a-z0-9-]{1,50}$");

	private final AssistantEvalService evalService;

	@PostMapping("/eval")
	public ResponseEntity<EvalReport> eval(
			@RequestParam String screenKey
	) {

		if (!SCREEN_KEY.matcher(screenKey).matches()) {
			throw new IllegalArgumentException("screenKey is invalid.");
		}

		return ResponseEntity.ok(evalService.run(screenKey));
	}
}
