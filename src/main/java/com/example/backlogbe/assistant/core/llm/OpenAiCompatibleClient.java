package com.example.backlogbe.assistant.core.llm;

import com.example.backlogbe.assistant.core.config.AssistantProperties;
import com.example.backlogbe.assistant.core.api.dto.ChatMessage;
import com.example.backlogbe.assistant.core.api.dto.ToolCall;
import com.example.backlogbe.assistant.core.tool.AssistantTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// =========================================================
// OPENAI-COMPATIBLE CLIENT (LM Studio)
//
// POST {baseUrl}/chat/completions, non-streaming, HTTP/1.1,
// body serialize sẵn thành byte[] (có Content-Length, không chunked).
// Hỗ trợ tool calling (OpenAI tools format): gửi "tools", đọc message.tool_calls.
// Chỉ lấy message.content; bỏ reasoning / thinking.
// Giới hạn đồng thời nằm ở LlmConcurrencyLimiter (theo câu hỏi, không theo lần gọi).
// Log: finish_reason, token usage, số tool_calls, thời gian gọi;
// lỗi HTTP thì status + 300 ký tự đầu body. Không log nội dung hội thoại.
// =========================================================
@Slf4j
@Component
public class OpenAiCompatibleClient implements LlmClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	// Số ký tự body lỗi HTTP của LM Studio được ghi log
	private static final int ERROR_BODY_LOG_LIMIT = 300;

	// <think>...</think> (một số model nhét reasoning vào content)
	private static final Pattern THINK_BLOCK =
			Pattern.compile("(?is)<think>.*?</think>");

	// <think> không đóng: toàn bộ phần sau là reasoning
	private static final Pattern THINK_UNCLOSED =
			Pattern.compile("(?is)<think>.*$");

	// gpt-oss (harmony format) nếu server không tách kênh
	private static final String HARMONY_FINAL = "<|channel|>final<|message|>";
	private static final Pattern HARMONY_TOKEN =
			Pattern.compile("<\\|[a-z_]+\\|>");

	private static final String CHAT_PATH = "/chat/completions";

	private final AssistantProperties props;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;
	private final RestClient restClient;
	private final Duration configuredTimeout;
	private final String chatUrl;

	public OpenAiCompatibleClient(
			AssistantProperties props,
			RestClient.Builder restClientBuilder,
			ObjectMapper objectMapper
	) {
		this.props = props;
		this.objectMapper = objectMapper;

		String baseUrl = stripTrailingSlash(props.getBaseUrl());
		this.chatUrl = baseUrl + CHAT_PATH;

		// Ép HTTP/1.1: JDK HttpClient mặc định thử nâng cấp h2c (Upgrade header),
		// một số server (LM Studio) xử lý không đúng.
		this.httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(CONNECT_TIMEOUT)
				.build();

		this.configuredTimeout = Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds()));

		this.restClient = restClientBuilder
				.baseUrl(baseUrl)
				.requestFactory(requestFactory(configuredTimeout))
				.build();
	}

	@Override
	public LlmResponse chat(
			List<ChatMessage> messages,
			List<AssistantTool> tools,
			Duration timeout
	) {

		// Serialize trước thành byte[] để gửi kèm Content-Length (không chunked)
		byte[] payload = serialize(buildBody(messages, tools));

		// Timeout lần gọi = min(cấu hình, thời gian còn lại service cho phép)
		Duration callTimeout = timeout != null && timeout.compareTo(configuredTimeout) < 0
				? timeout
				: configuredTimeout;

		RestClient client = callTimeout.equals(configuredTimeout)
				? restClient
				: restClient.mutate().requestFactory(requestFactory(callTimeout)).build();

		log.info(
				"LLM call start: url={} model={} tools={} timeoutMs={}",
				chatUrl,
				props.getModel(),
				tools == null ? 0 : tools.size(),
				callTimeout.toMillis()
		);

		long start = System.nanoTime();

		try {
			JsonNode response = client.post()
					.uri(CHAT_PATH)
					.contentType(MediaType.APPLICATION_JSON)
					.contentLength(payload.length)
					.accept(MediaType.APPLICATION_JSON)
					.body(payload)
					.retrieve()
					.body(JsonNode.class);

			if (response == null) {
				log.warn("LLM call: empty body durationMs={}", elapsedMs(start));
				throw new LlmEmptyException("none");
			}

			JsonNode choice = response.path("choices").path(0);
			JsonNode message = choice.path("message");
			JsonNode usage = response.path("usage");
			String finishReason = choice.path("finish_reason").asText("?");
			List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));

			// Chỉ log metadata, không log nội dung
			log.info(
					"LLM call: finish_reason={} promptTokens={} completionTokens={} toolCalls={} durationMs={}",
					finishReason,
					usage.path("prompt_tokens").asText("?"),
					usage.path("completion_tokens").asText("?"),
					toolCalls.size(),
					elapsedMs(start)
			);

			JsonNode promptTokens = usage.path("prompt_tokens");

			return new LlmResponse(
					extractAnswer(message.path("content").asText("")),
					toolCalls,
					finishReason,
					promptTokens.canConvertToInt() ? promptTokens.asInt() : null
			);

		} catch (RestClientResponseException e) {
			log.warn(
					"LLM call: HTTP {} durationMs={} body={}",
					e.getStatusCode().value(),
					elapsedMs(start),
					abbreviate(e.getResponseBodyAsString())
			);
			throw new LlmUnavailableException("LLM HTTP " + e.getStatusCode().value(), e);

		} catch (RestClientException e) {
			log.warn(
					"LLM call: failed {} durationMs={}",
					e.getClass().getSimpleName(),
					elapsedMs(start)
			);
			throw new LlmUnavailableException("LLM call failed: " + e.getClass().getSimpleName(), e);
		}
	}


	// =========================================================
	// REQUEST
	// =========================================================

	private Map<String, Object> buildBody(
			List<ChatMessage> messages,
			List<AssistantTool> tools
	) {

		Map<String, Object> body = new LinkedHashMap<>();

		body.put("model", props.getModel());
		body.put("messages", messages.stream().map(OpenAiCompatibleClient::toMessage).toList());

		if (tools != null && !tools.isEmpty()) {
			body.put("tools", tools.stream().map(OpenAiCompatibleClient::toTool).toList());
			body.put("tool_choice", "auto");
		}

		body.put("max_tokens", props.getMaxTokens());
		body.put("temperature", props.getTemperature());
		body.put("stream", false);

		if (props.getReasoningEffort() != null && !props.getReasoningEffort().isBlank()) {
			body.put("reasoning_effort", props.getReasoningEffort().trim());
		}

		return body;
	}

	private static Map<String, Object> toMessage(ChatMessage m) {

		Map<String, Object> msg = new LinkedHashMap<>();

		msg.put("role", m.role());
		// Một số server không nhận content null -> gửi chuỗi rỗng
		msg.put("content", m.content() != null ? m.content() : "");

		if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
			msg.put("tool_calls", m.toolCalls().stream()
					.map(c -> Map.of(
							"id", c.id(),
							"type", "function",
							"function", Map.of(
									"name", c.name(),
									"arguments", c.arguments() != null ? c.arguments() : "{}"
							)
					))
					.toList());
		}

		if (m.toolCallId() != null) {
			msg.put("tool_call_id", m.toolCallId());
		}

		return msg;
	}

	private static Map<String, Object> toTool(AssistantTool tool) {

		return Map.of(
				"type", "function",
				"function", Map.of(
						"name", tool.name(),
						"description", tool.description(),
						"parameters", tool.parameters()
				)
		);
	}

	private byte[] serialize(Map<String, Object> body) {

		try {
			return objectMapper.writeValueAsBytes(body);
		} catch (JsonProcessingException e) {
			// Lỗi nội bộ -> 500 INTERNAL
			throw new UncheckedIOException("Cannot serialize LLM request", e);
		}
	}


	// =========================================================
	// RESPONSE
	// =========================================================

	private static List<ToolCall> parseToolCalls(JsonNode node) {

		if (node == null || !node.isArray()) {
			return List.of();
		}

		List<ToolCall> calls = new ArrayList<>();
		int index = 0;

		for (JsonNode call : node) {

			JsonNode function = call.path("function");
			String name = function.path("name").asText("");

			if (name.isBlank()) {
				continue;
			}

			// arguments chuẩn là chuỗi JSON; một số server trả object
			JsonNode argsNode = function.path("arguments");
			String args = argsNode.isTextual()
					? argsNode.asText()
					: argsNode.isMissingNode() || argsNode.isNull() ? "{}" : argsNode.toString();

			String id = call.path("id").asText("");
			if (id.isBlank()) {
				id = "call_" + index;
			}

			calls.add(new ToolCall(id, name, args));
			index++;
		}

		return calls;
	}

	// Bỏ reasoning còn sót trong content, chỉ giữ câu trả lời cuối
	static String extractAnswer(String content) {

		if (content == null) {
			return "";
		}

		String text = content;

		int finalIdx = text.lastIndexOf(HARMONY_FINAL);
		if (finalIdx >= 0) {
			text = text.substring(finalIdx + HARMONY_FINAL.length());
		}

		text = THINK_BLOCK.matcher(text).replaceAll("");
		text = THINK_UNCLOSED.matcher(text).replaceAll("");
		text = HARMONY_TOKEN.matcher(text).replaceAll("");

		return text.trim();
	}


	// =========================================================
	// HELPER
	// =========================================================

	// Dùng chung HttpClient (HTTP/1.1, connect timeout 5s); read timeout theo từng lần gọi
	private JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {

		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);

		return factory;
	}

	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	// 300 ký tự đầu, gộp xuống dòng để log 1 dòng
	private static String abbreviate(String text) {

		if (text == null || text.isEmpty()) {
			return "";
		}

		String oneLine = text.replaceAll("\\s+", " ").trim();

		return oneLine.length() > ERROR_BODY_LOG_LIMIT
				? oneLine.substring(0, ERROR_BODY_LOG_LIMIT) + "..."
				: oneLine;
	}

	private static String stripTrailingSlash(String url) {

		if (url == null || url.isBlank()) {
			throw new IllegalStateException("assistant.llm.base-url is required");
		}

		String trimmed = url.trim();

		return trimmed.endsWith("/")
				? trimmed.substring(0, trimmed.length() - 1)
				: trimmed;
	}
}
