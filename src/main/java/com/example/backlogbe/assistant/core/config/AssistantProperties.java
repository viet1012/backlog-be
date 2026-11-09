package com.example.backlogbe.assistant.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// =========================================================
// ASSISTANT CONFIG (assistant.*)
//
// assistant.name  : tên mascot hiển thị / xưng trong prompt (placeholder {{assistantName}} trong .md)
// assistant.llm.* : LLM nội bộ chạy LM Studio, API OpenAI-compatible, không cần API key.
// assistant.audit.*: file audit JSONL của /api/assistant/chat (enabled, dir).
// assistant.template-answers.enabled: câu trả lời template (không gọi LLM) cho intent có kết luận tính sẵn.
// =========================================================
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {

	public static final String DEFAULT_NAME = "AI Assistant";

	private String name = DEFAULT_NAME;

	private Llm llm = new Llm();

	private Audit audit = new Audit();

	private TemplateAnswers templateAnswers = new TemplateAnswers();

	// Trống -> DEFAULT_NAME
	public String resolvedName() {
		return name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
	}

	@Getter
	@Setter
	public static class Llm {

		// vd http://192.168.122.16:1234/v1 (không có "/" ở cuối)
		private String baseUrl = "http://192.168.122.16:1234/v1";

		private String model = "openai/gpt-oss-20b";

		// Timeout đọc response (giây). Connect timeout cố định 5 giây.
		private int timeoutSeconds = 180;

		// Gồm cả token reasoning của gpt-oss
		private int maxTokens = 3000;

		private double temperature = 0.2;

		// Số request đồng thời tối đa tới LLM. Vượt -> 503.
		private int maxConcurrent = 4;

		// Gửi "reasoning_effort" nếu có giá trị. Mặc định để trống (không gửi):
		// mức suy luận đặt bằng dòng "Reasoning: low" trong system prompt.
		private String reasoningEffort = "";

		// Prompt (ước tính trước khi gọi, hoặc promptTokens thực tế) vượt mức này -> log WARN (kèm screenKey, intent).
		// Context Length model hiện 16384; chừa chỗ cho câu trả lời + reasoning.
		private int promptTokenWarn = 13000;

		// Ước tính token từ số ký tự prompt (tiếng Việt có dấu + nhãn dữ liệu); hiệu chỉnh theo audit
		// (promptChars / promptTokens của lần gọi đầu). Chưa gồm mô tả tool.
		private double charsPerToken = 3.0;
	}

	// assistant.audit.* – file audit JSONL (logger "assistant.audit", cấu hình appender trong logback-spring.xml)
	@Getter
	@Setter
	public static class Audit {

		// false -> không ghi audit
		private boolean enabled = true;

		// Thư mục file audit (logback đọc trực tiếp key này); file: <dir>/assistant-audit-yyyy-MM-dd.jsonl
		private String dir = "logs/assistant";
	}

	// assistant.template-answers.* – câu trả lời do code render (AnswerGuide.directAnswer), không gọi LLM
	@Getter
	@Setter
	public static class TemplateAnswers {

		// false -> mọi câu hỏi đi qua LLM (khối KẾT LUẬN vẫn chèn như trước)
		private boolean enabled = true;
	}
}
