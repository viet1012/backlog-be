package com.example.backlogbe.assistant.core.eval;

import com.example.backlogbe.assistant.core.api.dto.AssistantChatRequest;
import com.example.backlogbe.assistant.core.api.dto.AssistantChatResponse;
import com.example.backlogbe.assistant.core.api.dto.AssistantSource;
import com.example.backlogbe.assistant.core.chat.ChatOrchestrator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// =========================================================
// EVAL TRỢ LÝ (chỉ profile dev)
//
// Đọc resources/assistant/screens/{screenKey}/eval.json, hỏi lần lượt từng câu qua ChatOrchestrator
// (cùng luồng với /api/assistant/chat, không qua rate limit), so câu trả lời với
// mustContain / mustNotContain (không phân biệt hoa thường).
// Không phải unit test: gọi LLM thật, chạy tuần tự nên có thể mất vài phút.
// =========================================================
@Slf4j
@Service
@Profile("dev")
@RequiredArgsConstructor
public class AssistantEvalService {

	private static final String EVAL_PATH = "assistant/screens/%s/eval.json";
	private static final String LANG = "vi";

	private final ChatOrchestrator chatOrchestrator;
	private final ObjectMapper objectMapper;

	public EvalReport run(String screenKey) {

		List<EvalCase> cases = loadCases(screenKey);
		List<EvalResult> results = new ArrayList<>();

		long start = System.nanoTime();

		for (int i = 0; i < cases.size(); i++) {
			results.add(runCase(screenKey, i + 1, cases.get(i)));
		}

		int passed = (int) results.stream().filter(EvalResult::pass).count();

		log.info("Assistant eval screenKey={} total={} passed={}", screenKey, results.size(), passed);

		return new EvalReport(
				screenKey,
				results.size(),
				passed,
				results.size() - passed,
				elapsedMs(start),
				results
		);
	}

	private EvalResult runCase(String screenKey, int index, EvalCase c) {

		long start = System.nanoTime();

		try {
			AssistantChatResponse response = chatOrchestrator.chat(new AssistantChatRequest(
					screenKey,
					c.month() != null && !c.month().isBlank() ? Map.of("month", c.month()) : Map.of(),
					c.question(),
					List.of(),
					LANG
			));

			String answer = response.answer() != null ? response.answer() : "";
			String lower = answer.toLowerCase(Locale.ROOT);

			List<String> missing = new ArrayList<>();
			for (String s : nullToEmpty(c.mustContain())) {
				if (!lower.contains(s.toLowerCase(Locale.ROOT))) {
					missing.add(s);
				}
			}

			List<String> unexpected = new ArrayList<>();
			for (String s : nullToEmpty(c.mustNotContain())) {
				if (lower.contains(s.toLowerCase(Locale.ROOT))) {
					unexpected.add(s);
				}
			}

			List<String> tools = response.sources() == null
					? List.of()
					: response.sources().stream().map(AssistantEvalService::sourceText).toList();

			return new EvalResult(
					index, c.question(), c.month(),
					missing.isEmpty() && unexpected.isEmpty(),
					missing, unexpected, answer, tools, null,
					elapsedMs(start)
			);

		} catch (RuntimeException e) {
			// Lỗi 1 câu (LLM bận, timeout, 400...) không dừng cả lượt eval
			return new EvalResult(
					index, c.question(), c.month(),
					false, List.of(), List.of(), null, List.of(),
					e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""),
					elapsedMs(start)
			);
		}
	}

	private List<EvalCase> loadCases(String screenKey) {

		ClassPathResource resource = new ClassPathResource(EVAL_PATH.formatted(screenKey));

		if (!resource.exists()) {
			throw new IllegalArgumentException("Eval file not found: " + EVAL_PATH.formatted(screenKey));
		}

		try (InputStream in = resource.getInputStream()) {
			List<EvalCase> cases = objectMapper.readValue(in, new TypeReference<List<EvalCase>>() {
			});
			return cases != null ? cases : List.of();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read eval file: " + EVAL_PATH.formatted(screenKey), e);
		}
	}

	// "<tool> <div> <cost> (yyyy-MM)"
	private static String sourceText(AssistantSource s) {

		StringBuilder sb = new StringBuilder(s.tool());

		if (s.div() != null) {
			sb.append(' ').append(s.div());
		}

		if (s.cost() != null) {
			sb.append(' ').append(s.cost());
		}

		if (s.month() != null) {
			sb.append(" (").append(s.month()).append(')');
		}

		return sb.toString();
	}

	private static List<String> nullToEmpty(List<String> list) {
		return list != null ? list : List.of();
	}

	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
