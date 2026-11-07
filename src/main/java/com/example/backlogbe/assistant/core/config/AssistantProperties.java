package com.example.backlogbe.assistant.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// =========================================================
// ASSISTANT - LLM CONFIG (assistant.llm.*)
//
// LLM nội bộ chạy LM Studio, API OpenAI-compatible, không cần API key.
// =========================================================
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "assistant.llm")
public class AssistantProperties {

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
}
