package com.example.backlogbe.assistant.core.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// =========================================================
// Số liệu 1 request /api/assistant/chat, gom dần trong ChatOrchestrator (1 thread / request, không cần đồng bộ),
// ghi 1 dòng JSONL khi kết thúc qua AssistantAuditLogger. Field null -> ghi null.
// =========================================================
public class AssistantAuditTrace {

	// 1 lần gọi LLM; finishReason = "error" khi lần gọi lỗi (timeout / HTTP / không kết nối)
	public record LlmCall(long ms, Integer promptTokens, Integer completionTokens, String finishReason) {
	}

	// 1 lần chạy tool; round = 0 là pre-fetch; output: kết quả gửi model (chỉ ghi cho pre-fetch, null với tool model gọi)
	public record ToolRun(String name, int round, long ms, String outcome, String output) {
	}

	public static final String SOURCE_CHAT = "chat";
	public static final String SOURCE_EVAL = "eval";

	public static final String ANSWER_LLM = "llm";
	public static final String ANSWER_TEMPLATE = "template";

	private final long startNanos = System.nanoTime();

	// "chat" (POST /api/assistant/chat) | "eval" (POST /api/assistant/eval)
	final String source;
	String userId;
	String screenKey;
	String lang;
	Map<String, String> params;
	String question;
	String answer;
	boolean ok;
	String errorCode;
	String errorMessage;
	Long contextMs;
	Long askedMonthsMs;
	Long prefetchMs;
	String model;
	String reasoningEffort;
	// AnswerGuide: null = màn hình không trả hướng dẫn; toolChoice mặc định "auto"
	String answerIntent;
	// Chuỗi debug phân loại intent của màn hình (AnswerGuide.intentMatch); null = không có
	String intentMatch;
	boolean forcedConclusion;
	String toolChoice = "auto";
	// Nguồn câu trả lời: "llm" | "template" (AnswerGuide.directAnswer, không gọi LLM); null = lỗi trước khi chọn
	String answerSource;
	String conclusion;
	Integer promptChars;
	Integer estimatedPromptTokens;

	final List<ToolRun> prefetchTools = new ArrayList<>();
	final List<ToolRun> toolCalls = new ArrayList<>();
	final List<LlmCall> llmCalls = new ArrayList<>();

	public AssistantAuditTrace(String source, String userId) {
		this.source = source;
		this.userId = userId;
	}

	long totalMs() {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	public void request(String screenKey, String lang, Map<String, String> params, String question) {
		this.screenKey = screenKey;
		this.lang = lang;
		this.params = params;
		this.question = question;
	}

	public void model(String model, String reasoningEffort) {
		this.model = model;
		this.reasoningEffort = reasoningEffort;
	}

	public void contextMs(long ms) {
		this.contextMs = ms;
	}

	public void askedMonthsMs(long ms) {
		this.askedMonthsMs = ms;
	}

	public void prefetchMs(long ms) {
		this.prefetchMs = ms;
	}

	public void llmCall(long ms, Integer promptTokens, Integer completionTokens, String finishReason) {
		llmCalls.add(new LlmCall(ms, promptTokens, completionTokens, finishReason));
	}

	// output chỉ giữ cho pre-fetch (round 0)
	public void tool(String name, int round, long ms, String outcome, String output) {
		if (round == 0) {
			prefetchTools.add(new ToolRun(name, round, ms, outcome, output));
		} else {
			toolCalls.add(new ToolRun(name, round, ms, outcome, null));
		}
	}

	// Prompt gửi lần gọi LLM đầu: tổng ký tự content mọi message (chưa gồm mô tả tool), token ước tính,
	// và toàn văn khối kết luận (AnswerGuide.conclusion; null = không có)
	public void prompt(int promptChars, int estimatedPromptTokens, String conclusion) {
		this.promptChars = promptChars;
		this.estimatedPromptTokens = estimatedPromptTokens;
		this.conclusion = conclusion;
	}

	// tool_choice none bị bỏ qua (server vẫn trả tool_calls, content rỗng) -> đã gọi lại không gửi tools
	public void retriedWithoutTools() {
		this.toolChoice = "none+retry_without_tools";
	}

	// Hướng dẫn trả lời của màn hình (AnswerGuide): loại câu hỏi, có chèn khối kết luận không, tool_choice thực gửi
	public void answerGuide(String intent, boolean forcedConclusion, String toolChoice) {
		this.answerIntent = intent;
		this.forcedConclusion = forcedConclusion;
		this.toolChoice = toolChoice;
		this.answerSource = ANSWER_LLM;
	}

	// Câu trả lời template (AnswerGuide.directAnswer): không gọi LLM, không gửi tool -> toolChoice null
	public void templateAnswer(String intent) {
		this.answerIntent = intent;
		this.forcedConclusion = false;
		this.toolChoice = null;
		this.answerSource = ANSWER_TEMPLATE;
	}

	// Cách màn hình phân loại câu hỏi (intent + từ khóa đã khớp)
	public void intentMatch(String intentMatch) {
		this.intentMatch = intentMatch;
	}

	public String answerIntent() {
		return answerIntent;
	}

	public int llmCallCount() {
		return llmCalls.size();
	}

	public String answerSource() {
		return answerSource;
	}

	public void success(String answer) {
		this.ok = true;
		this.answer = answer;
	}

	public void failure(String errorCode, String errorMessage) {
		this.ok = false;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
	}
}
