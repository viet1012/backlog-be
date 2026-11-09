package com.example.backlogbe.assistant.core.audit;

import com.example.backlogbe.assistant.core.config.AssistantProperties;
import com.example.backlogbe.logging.RequestTiming;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// =========================================================
// AUDIT LOG TRỢ LÝ AI (tra lại câu hỏi / câu trả lời sau này)
//
// Logger "assistant.audit" -> appender riêng trong logback-spring.xml:
//   <assistant.audit.dir>/assistant-audit-yyyy-MM-dd.jsonl, UTF-8, giữ 90 ngày, ghi bất đồng bộ,
//   KHÔNG vào log chung. assistant.audit.enabled = false -> không ghi.
// Mỗi request /api/assistant/chat: đúng 1 dòng JSON (thành công hoặc lỗi), field xem docs/assistant/README.md.
// Đây là nơi DUY NHẤT được ghi câu hỏi / câu trả lời / params; log chung vẫn không ghi.
// Lỗi khi ghi audit không làm hỏng request (chỉ log WARN tên lỗi, không kèm nội dung).
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantAuditLogger {

	private static final Logger AUDIT = LoggerFactory.getLogger("assistant.audit");

	// Giới hạn độ dài errorMessage (stack / SQL dài)
	private static final int MAX_ERROR_MESSAGE = 1000;

	// Giới hạn độ dài output của tool pre-fetch
	private static final int MAX_TOOL_OUTPUT = 2000;

	private final AssistantProperties properties;
	private final ObjectMapper objectMapper;

	public boolean enabled() {
		return properties.getAudit().isEnabled();
	}

	public void write(AssistantAuditTrace t) {

		if (!enabled()) {
			return;
		}

		try {
			Map<String, Object> line = new LinkedHashMap<>();

			line.put("timestamp", OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString());
			line.put("rid", RequestTiming.rid());
			line.put("source", t.source);
			line.put("screenKey", t.screenKey);
			line.put("lang", t.lang);
			line.put("userId", t.userId);
			line.put("params", t.params);
			line.put("question", t.question);
			line.put("answer", t.answer);
			line.put("ok", t.ok);
			line.put("errorCode", t.errorCode);
			line.put("errorMessage", abbreviate(t.errorMessage));
			line.put("totalMs", t.totalMs());
			line.put("contextMs", t.contextMs);
			line.put("askedMonthsMs", t.askedMonthsMs);
			line.put("prefetchMs", t.prefetchMs);
			line.put("prefetchTools", tools(t.prefetchTools, false));
			line.put("llmCallCount", t.llmCalls.size());
			line.put("llmCalls", llmCalls(t.llmCalls));
			line.put("toolCalls", tools(t.toolCalls, true));
			line.put("model", t.model);
			line.put("reasoningEffort", t.reasoningEffort);
			line.put("answerIntent", t.answerIntent);
			line.put("answerSource", t.answerSource);
			line.put("intentMatch", t.intentMatch);
			line.put("forcedConclusion", t.forcedConclusion);
			line.put("toolChoice", t.toolChoice);
			line.put("conclusion", t.conclusion);
			line.put("promptChars", t.promptChars);
			line.put("estimatedPromptTokens", t.estimatedPromptTokens);

			AUDIT.info(objectMapper.writeValueAsString(line));

		} catch (Exception e) {
			log.warn("Assistant audit: cannot write rid={} error={}", RequestTiming.rid(), e.getClass().getSimpleName());
		}
	}

	private static List<Map<String, Object>> tools(List<AssistantAuditTrace.ToolRun> runs, boolean withRound) {

		List<Map<String, Object>> result = new ArrayList<>();

		for (AssistantAuditTrace.ToolRun r : runs) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", r.name());
			if (withRound) {
				m.put("round", r.round());
			}
			m.put("ms", r.ms());
			m.put("outcome", r.outcome());
			// Chỉ pre-fetch (withRound = false): kết quả gửi model, cắt MAX_TOOL_OUTPUT ký tự
			if (!withRound) {
				m.put("output", abbreviate(r.output(), MAX_TOOL_OUTPUT));
			}
			result.add(m);
		}

		return result;
	}

	private static List<Map<String, Object>> llmCalls(List<AssistantAuditTrace.LlmCall> calls) {

		List<Map<String, Object>> result = new ArrayList<>();

		for (AssistantAuditTrace.LlmCall c : calls) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("ms", c.ms());
			m.put("promptTokens", c.promptTokens());
			m.put("completionTokens", c.completionTokens());
			m.put("finishReason", c.finishReason());
			result.add(m);
		}

		return result;
	}

	private static String abbreviate(String text) {
		return abbreviate(text, MAX_ERROR_MESSAGE);
	}

	private static String abbreviate(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
	}
}
