package com.example.backlogbe.assistant.core.chat;

import com.example.backlogbe.assistant.core.api.AssistantExceptionHandler;
import com.example.backlogbe.assistant.core.api.dto.AssistantChatRequest;
import com.example.backlogbe.assistant.core.audit.AssistantAuditLogger;
import com.example.backlogbe.assistant.core.audit.AssistantAuditTrace;
import com.example.backlogbe.assistant.core.config.AssistantProperties;
import com.example.backlogbe.assistant.core.api.dto.AssistantChatResponse;
import com.example.backlogbe.assistant.core.api.dto.AssistantSource;
import com.example.backlogbe.assistant.core.api.dto.AssistantSuggestion;
import com.example.backlogbe.assistant.core.api.dto.ChatMessage;
import com.example.backlogbe.assistant.core.api.dto.ToolCall;
import com.example.backlogbe.assistant.core.llm.LlmClient;
import com.example.backlogbe.assistant.core.llm.LlmConcurrencyLimiter;
import com.example.backlogbe.assistant.core.llm.LlmEmptyException;
import com.example.backlogbe.assistant.core.llm.LlmResponse;
import com.example.backlogbe.assistant.core.llm.LlmTruncatedException;
import com.example.backlogbe.assistant.core.llm.LlmUnavailableException;
import com.example.backlogbe.assistant.core.month.QuestionMonthDetector;
import com.example.backlogbe.assistant.core.month.ToolMonthScope;
import com.example.backlogbe.assistant.core.postprocess.AnswerPostProcessor;
import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import com.example.backlogbe.assistant.core.prompt.PromptBuilder;
import com.example.backlogbe.assistant.core.screen.AnswerGuide;
import com.example.backlogbe.assistant.core.screen.ScreenModule;
import com.example.backlogbe.assistant.core.screen.ScreenRegistry;
import com.example.backlogbe.assistant.core.tool.AssistantTool;
import com.example.backlogbe.logging.RequestTiming;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// =========================================================
// CHAT ORCHESTRATOR (chung cho mọi màn hình; mọi thứ riêng màn hình lấy qua ScreenRegistry)
//
// validate -> hướng dẫn trả lời (ScreenModule.answerGuide, chỉ câu hỏi theo tháng đang xem)
//   -> có directAnswer + assistant.template-answers.enabled: trả câu trả lời template ngay (hậu xử lý + suggestions),
//      KHÔNG gọi LLM, không context / pre-fetch, không giữ suất LLM (audit answerSource="template", llmCallCount=0)
// -> context theo screenKey (ScreenModule.buildContext)
//   -> tháng nhắc trong câu hỏi (QuestionMonthDetector): màn hình hỗ trợ tháng thì dựng thêm
//      context rút gọn tối đa 2 tháng được hỏi (ScreenModule.buildCompactContext)
//   -> pre-fetch tool (ScreenModule.prefetchRules) theo tháng được hỏi -> lưu ý tháng ở cuối câu hỏi
//   -> hướng dẫn trả lời (đã tính ở bước đầu): khối kết luận đặt ngay trước
//      "Câu hỏi:"; toolsSufficient + mọi tool pre-fetch ra kết quả -> gọi LLM với tool_choice "none"
//      (server vẫn trả tool_calls: không chạy; content rỗng thì gọi lại 1 lần không gửi tools)
//   -> prompt -> LLM (+ tool calling; tham số month của tool qua ToolMonthScope)
//   -> hậu xử lý (AnswerPostProcessor): thay mã nội bộ, (vi) dấu phẩy thập phân thành dấu chấm.
// Log chung: chỉ screenKey, số lần gọi LLM, tên tool, thời gian xử lý, lỗi.
// KHÔNG log câu hỏi / câu trả lời / params / tham số tool vào log chung.
// Audit (AssistantAuditLogger, file riêng): 1 dòng / request gồm câu hỏi, câu trả lời, params, thời gian từng bước.
// Kết quả tool: chỉ log (DEBUG) khi chạy profile "dev".
// =========================================================
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

	static final int MAX_HISTORY = 6;
	static final int MAX_QUESTION_LENGTH = 2000;
	static final int MAX_HISTORY_CONTENT_LENGTH = 4000;
	static final int MAX_PARAMS = 20;
	static final int MAX_PARAM_VALUE_LENGTH = 100;

	private static final Pattern SCREEN_KEY = Pattern.compile("^[a-z0-9-]{1,50}$");

	// Số vòng chạy tool tối đa cho 1 câu hỏi
	static final int MAX_TOOL_ROUNDS = 3;

	// Tổng thời gian tối đa cho 1 câu hỏi (FE timeout 190s)
	static final Duration MAX_TOTAL_DURATION = Duration.ofSeconds(170);

	// Còn ít hơn mức này thì không gọi LLM nữa
	private static final Duration MIN_CALL_DURATION = Duration.ofSeconds(1);

	private final ScreenRegistry screenRegistry;
	private final PromptBuilder promptBuilder;
	private final AnswerPostProcessor answerPostProcessor;
	private final LlmClient llmClient;
	private final LlmConcurrencyLimiter concurrencyLimiter;
	private final ObjectMapper objectMapper;
	private final Environment environment;
	private final AssistantProperties assistantProperties;
	private final AssistantAuditLogger auditLogger;

	// Profile được log toàn bộ kết quả tool (DEBUG)
	private static final String DEV_PROFILE = "dev";

	public AssistantChatResponse chat(AssistantChatRequest request) {
		return chat(request, null);
	}

	/**
	 * @param userId mã nhân viên (header X-Employee-Id, đã kiểm định dạng; null = không có) – chỉ ghi vào audit log
	 */
	public AssistantChatResponse chat(AssistantChatRequest request, String userId) {
		return chat(request, userId, AssistantAuditTrace.SOURCE_CHAT);
	}

	/**
	 * @param source nguồn request cho audit log: AssistantAuditTrace.SOURCE_CHAT | SOURCE_EVAL
	 */
	public AssistantChatResponse chat(AssistantChatRequest request, String userId, String source) {
		return chatWithTrace(request, new AssistantAuditTrace(source, userId));
	}

	/**
	 * Tên riêng (không overload chat) để tránh mơ hồ khi gọi chat(request, null).
	 *
	 * @param trace audit trace do người gọi tạo (eval đọc lại llmCallCount / answerSource sau khi chạy)
	 */
	public AssistantChatResponse chatWithTrace(AssistantChatRequest request, AssistantAuditTrace trace) {

		// Audit: đúng 1 dòng / request (thành công hoặc lỗi), ghi ở finally
		try {
			AssistantChatResponse response = doChat(request, trace);
			trace.success(response.answer());
			return response;

		} catch (RuntimeException e) {
			trace.failure(AssistantExceptionHandler.codeOf(e), e.getMessage());
			throw e;

		} finally {
			auditLogger.write(trace);
		}
	}

	private AssistantChatResponse doChat(AssistantChatRequest request, AssistantAuditTrace trace) {

		if (request == null) {
			throw new IllegalArgumentException("Request body is required.");
		}

		// Giá trị thô trước validate (validate lỗi vẫn có câu hỏi trong audit); ghi đè bằng giá trị đã validate
		trace.request(request.screenKey(), request.lang(), null, abbreviate(request.question(), MAX_QUESTION_LENGTH));

		String screenKey = validateScreenKey(request.screenKey());
		String question = validateQuestion(request.question());
		Map<String, String> params = validateParams(request.params());
		List<ChatMessage> history = sanitizeHistory(request.history());
		String lang = validateLang(request.lang());

		trace.request(screenKey, lang, params, question);
		trace.model(assistantProperties.getLlm().getModel(), reasoningEffort(question));

		long start = System.nanoTime();
		int[] llmCalls = {0};
		// Tool đã gọi thành công (giữ thứ tự, bỏ trùng)
		Set<AssistantSource> sources = new LinkedHashSet<>();
		// Tháng câu trả lời dùng khi khác tháng đang xem (null = tháng đang xem)
		String[] answeredMonth = {null};
		// Log: số tháng phụ đã dựng context; promptTokens của lần gọi LLM đầu tiên (prompt gốc, gồm khối tháng phụ)
		int[] extraMonths = {0};
		Integer[] promptTokens = {null};
		boolean ok = false;
		String answerSource = AssistantAuditTrace.ANSWER_LLM;

		try {
			ScreenModule module = screenRegistry.find(screenKey);
			screenRegistry.checkParams(screenKey, params);

			boolean supportsMonth = module != null && module.supportsMonthQuestions();

			// Tháng nhắc trong câu hỏi (QuestionMonthDetector); màn hình hỗ trợ tháng: dựng thêm context tháng được hỏi
			QuestionMonthDetector.MonthPlan plan =
					QuestionMonthDetector.plan(question, supportsMonth, params, YearMonth.now());

			// Hướng dẫn trả lời của màn hình (chỉ câu hỏi theo tháng đang xem; params tháng đang xem = params gốc)
			AnswerGuide guide = answerGuide(module, screenKey, question, plan, params, lang);
			trace.intentMatch(guide != null ? guide.intentMatch() : null);

			// Câu trả lời template: code render sẵn -> không context / pre-fetch / LLM, không giữ suất LLM
			if (guide != null && guide.hasDirectAnswer() && assistantProperties.getTemplateAnswers().isEnabled()) {

				trace.templateAnswer(guide.intent());
				answerSource = AssistantAuditTrace.ANSWER_TEMPLATE;

				String answer = answerPostProcessor.process(guide.directAnswer(), screenKey, lang);
				ok = true;

				return new AssistantChatResponse(answer, List.of(), null, suggestions(guide));
			}

			// Giữ 1 suất đồng thời cho cả câu hỏi (context + mọi lần gọi LLM)
			String answer = concurrencyLimiter.run(() -> {

				// Hạn chót cho cả câu hỏi (context + tool + mọi lần gọi LLM)
				long deadlineNanos = System.nanoTime() + MAX_TOTAL_DURATION.toNanos();

				long stepStart = System.nanoTime();
				String context = screenRegistry.buildContext(screenKey, params, lang, question);
				trace.contextMs(logStep("context", stepStart));

				List<AssistantTool> tools = screenRegistry.tools(screenKey);

				List<YearMonth> builtMonths = new ArrayList<>();
				List<YearMonth> noDataMonths = new ArrayList<>();
				if (plan != null && !plan.extra().isEmpty()) {
					stepStart = System.nanoTime();
					context = appendAskedMonths(module, screenKey, context, plan, lang, builtMonths, noDataMonths);
					trace.askedMonthsMs(logStep("askedMonths", stepStart));
				}
				extraMonths[0] = builtMonths.size();

				ToolMonthScope monthScope = new ToolMonthScope(
						plan == null ? null : plan.viewed(),
						params,
						builtMonths,
						module != null ? module::paramsForMonth : (p, m) -> p
				);

				// Pre-fetch tool theo PrefetchRule (theo tháng được hỏi): kết quả nối vào cuối context, tool vào sources;
				// quy tắc khớp mà không ra kết quả -> fallbackHint vào cuối câu hỏi
				List<String> questionHints = new ArrayList<>();
				PrefetchStats prefetchStats = new PrefetchStats();
				stepStart = System.nanoTime();
				context = prefetch(module, screenKey, question, context, tools, prefetchMonths(plan, builtMonths),
						monthScope, lang, sources, questionHints, prefetchStats, trace);
				trace.prefetchMs(logStep("prefetch", stepStart));

				String annotated = annotateQuestion(question, plan, builtMonths, noDataMonths, lang);

				// Trả lời theo tháng khác tháng đang xem (không nhắc tháng đang xem) -> answeredMonth
				if (plan != null && !builtMonths.isEmpty() && !plan.viewedMentioned()) {
					answeredMonth[0] = builtMonths.get(0).toString();
				}

				// Hướng dẫn trả lời của màn hình (đã tính ở trên): khối kết luận trước "Câu hỏi:";
				// pre-fetch đã đủ -> tool_choice none
				String conclusion = guide != null && guide.hasConclusion() ? guide.conclusion() : null;
				boolean toolsDisabled = guide != null && guide.toolsSufficient() && prefetchStats.allSucceeded();
				String toolChoice = toolsDisabled ? LlmClient.TOOL_CHOICE_NONE : LlmClient.TOOL_CHOICE_AUTO;

				trace.answerGuide(guide != null ? guide.intent() : null, conclusion != null, toolChoice);

				List<ChatMessage> messages =
						promptBuilder.build(screenKey, context, history, annotated, lang, questionHints, conclusion);

				// Kích thước prompt (audit + WARN khi gần Context Length)
				int promptChars = promptChars(messages);
				int estimatedTokens = estimateTokens(promptChars);
				trace.prompt(promptChars, estimatedTokens, conclusion);

				if (estimatedTokens > assistantProperties.getLlm().getPromptTokenWarn()) {
					log.warn("Assistant: prompt too large (estimated) rid={} screenKey={} intent={} promptChars={} estimatedTokens={} warnAt={}",
							RequestTiming.rid(), screenKey, trace.answerIntent(), promptChars, estimatedTokens,
							assistantProperties.getLlm().getPromptTokenWarn());
				}

				String raw = runConversation(
						screenKey,
						messages,
						tools,
						toolChoice,
						params,
						monthScope,
						lang,
						llmCalls,
						promptTokens,
						deadlineNanos,
						sources,
						trace
				);

				// Chốt chặn cuối: thay mã nội bộ còn sót; vi: dấu phẩy thập phân -> dấu chấm
				return answerPostProcessor.process(raw, screenKey, lang);
			});

			ok = true;

			return new AssistantChatResponse(answer, List.copyOf(sources), answeredMonth[0]);

		} finally {
			log.info(
					"Assistant chat: rid={} screenKey={} ok={} answerSource={} llmCalls={} extraMonths={} promptTokens={} durationMs={}",
					RequestTiming.rid(),
					screenKey,
					ok,
					answerSource,
					llmCalls[0],
					extraMonths[0],
					promptTokens[0] != null ? promptTokens[0] : "?",
					elapsedMs(start)
			);
		}
	}


	// =========================================================
	// TOOL CALLING LOOP
	//
	// Gửi messages + tools. Có tool_calls -> chạy tool, thêm message "tool", gọi lại.
	// Tối đa MAX_TOOL_ROUNDS vòng chạy tool; quá thì lấy content của lần gọi cuối,
	// rỗng thì LLM_EMPTY (hoặc LLM_TRUNCATED nếu finish_reason=length).
	// Tổng thời gian tối đa MAX_TOTAL_DURATION: mỗi lần gọi LLM dùng timeout = thời gian còn lại;
	// hết giờ -> LlmUnavailableException (503 LLM_ERROR).
	// =========================================================

	private String runConversation(
			String screenKey,
			List<ChatMessage> initialMessages,
			List<AssistantTool> tools,
			String toolChoice,
			Map<String, String> screenParams,
			ToolMonthScope monthScope,
			String lang,
			int[] llmCalls,
			Integer[] promptTokens,
			long deadlineNanos,
			Set<AssistantSource> sources,
			AssistantAuditTrace trace
	) {

		List<ChatMessage> messages = new ArrayList<>(initialMessages);
		Map<String, AssistantTool> toolsByName = new HashMap<>();
		tools.forEach(tool -> toolsByName.put(tool.name(), tool));

		boolean toolsDisabled = LlmClient.TOOL_CHOICE_NONE.equals(toolChoice);
		// tool_choice none mà server vẫn trả tool_calls, content rỗng -> gọi lại 1 lần không gửi tools
		boolean retriedWithoutTools = false;
		List<AssistantTool> callTools = tools;

		int toolRounds = 0;

		while (true) {

			Duration remaining = Duration.ofNanos(deadlineNanos - System.nanoTime());

			if (remaining.compareTo(MIN_CALL_DURATION) < 0) {
				log.warn(
						"Assistant: total time limit reached rid={} screenKey={} llmCalls={} toolRounds={}",
						RequestTiming.rid(),
						screenKey,
						llmCalls[0],
						toolRounds
				);
				throw new LlmUnavailableException("Assistant total time limit exceeded");
			}

			long callStart = System.nanoTime();
			LlmResponse response;

			try {
				response = llmClient.chat(messages, callTools, remaining, toolChoice);
			} catch (RuntimeException e) {
				trace.llmCall(elapsedMs(callStart), null, null, "error");
				throw e;
			}

			trace.llmCall(elapsedMs(callStart), response.promptTokens(), response.completionTokens(),
					response.finishReason());
			llmCalls[0]++;

			// promptTokens của lần gọi đầu (prompt gốc)
			if (llmCalls[0] == 1) {
				promptTokens[0] = response.promptTokens();
			}

			// Số token thực tế (mọi lần gọi, gồm kết quả tool đã nối) gần Context Length -> WARN
			Integer actualPrompt = response.promptTokens();
			if (actualPrompt != null && actualPrompt > assistantProperties.getLlm().getPromptTokenWarn()) {
				log.warn("Assistant: prompt too large rid={} screenKey={} intent={} call={} promptTokens={} warnAt={}",
						RequestTiming.rid(), screenKey, trace.answerIntent(), llmCalls[0], actualPrompt,
						assistantProperties.getLlm().getPromptTokenWarn());
			}

			// Đã cấm gọi tool (pre-fetch đủ): không chạy tool_calls dù server trả về
			if (toolsDisabled && response.hasToolCalls()) {

				log.warn("Assistant: tool_calls ignored (tool_choice=none) rid={} screenKey={} count={}",
						RequestTiming.rid(), screenKey, response.toolCalls().size());

				if ((response.content() == null || response.content().isBlank()) && !retriedWithoutTools) {
					retriedWithoutTools = true;
					callTools = List.of();
					trace.retriedWithoutTools();
					continue;
				}

				return answerOrThrow(response);
			}

			if (!response.hasToolCalls() || toolRounds >= MAX_TOOL_ROUNDS) {
				if (response.hasToolCalls()) {
					log.warn("Assistant: tool round limit reached rid={} screenKey={} rounds={}",
							RequestTiming.rid(), screenKey, toolRounds);
				}
				return answerOrThrow(response);
			}

			toolRounds++;
			messages.add(ChatMessage.assistantToolCalls(response.content(), response.toolCalls()));

			for (ToolCall call : response.toolCalls()) {
				messages.add(ChatMessage.toolResult(
						call.id(),
						runTool(toolsByName.get(call.name()), call, screenParams, monthScope, lang, toolRounds, sources, trace)
				));
			}
		}
	}

	// =========================================================
	// PRE-FETCH (PrefetchRule)
	//
	// Mỗi quy tắc khớp: chạy các ToolCall qua runTool (round = 0 trong log; thành công -> vào sources).
	// Kết quả thành công gom theo tên tool, nối vào cuối context:
	//   "[Kết quả <tool> đã tra sẵn]\n<kết quả 1>\n\n<kết quả 2>…"
	// Tool không thuộc màn hình / lỗi -> bỏ qua (model vẫn tự gọi được như bình thường).
	// months: tháng chạy pre-fetch (null = tháng đang xem); mỗi tháng chạy đủ calls() của quy tắc
	//   với params của tháng đó (ToolMonthScope.paramsFor).
	// hintsOut: quy tắc khớp nhưng không có tool nào của quy tắc ra kết quả -> rule.fallbackHint(lang)
	//   (tool đã ra kết quả nhờ quy tắc khác cũng tính là có kết quả).
	// =========================================================

	private static final int PREFETCH_ROUND = 0;

	// Số lần chạy tool pre-fetch ra kết quả / lỗi (tool không thuộc màn hình, error=…, rỗng)
	static final class PrefetchStats {
		int succeeded;
		int failed;

		boolean allSucceeded() {
			return succeeded > 0 && failed == 0;
		}
	}

	private String prefetch(
			ScreenModule module,
			String screenKey,
			String question,
			String context,
			List<AssistantTool> tools,
			List<YearMonth> months,
			ToolMonthScope monthScope,
			String lang,
			Set<AssistantSource> sources,
			List<String> hintsOut,
			PrefetchStats statsOut,
			AssistantAuditTrace trace
	) {

		List<PrefetchRule> rules = module != null ? module.prefetchRules() : List.of();

		if (rules == null || rules.isEmpty()) {
			return context;
		}

		Map<String, AssistantTool> toolsByName = new HashMap<>();
		tools.forEach(tool -> toolsByName.put(tool.name(), tool));

		// tên tool -> các kết quả thành công (giữ thứ tự)
		Map<String, List<String>> results = new LinkedHashMap<>();
		// quy tắc khớp -> tên tool của quy tắc
		Map<PrefetchRule, Set<String>> matched = new LinkedHashMap<>();

		for (PrefetchRule rule : rules) {

			if (!rule.matches(question, screenKey, monthScope.paramsFor(null))) {
				continue;
			}

			Set<String> ruleTools = matched.computeIfAbsent(rule, r -> new LinkedHashSet<>());

			for (YearMonth month : months) {

				Map<String, String> params = monthScope.paramsFor(month);

				for (ToolCall call : rule.calls(question, params)) {

					ruleTools.add(call.name());
					AssistantTool tool = toolsByName.get(call.name());

					if (tool == null) {
						statsOut.failed++;
						continue;
					}

					String result = runTool(tool, call, params, monthScope, lang, PREFETCH_ROUND, sources, trace);

					if (result != null && !result.isBlank() && !result.startsWith("error=")) {
						results.computeIfAbsent(call.name(), k -> new ArrayList<>()).add(result);
						statsOut.succeeded++;
					} else {
						statsOut.failed++;
					}
				}
			}
		}

		for (Map.Entry<PrefetchRule, Set<String>> entry : matched.entrySet()) {
			boolean anyResult = entry.getValue().stream().anyMatch(results::containsKey);
			String hint = anyResult ? null : entry.getKey().fallbackHint(lang);
			if (hint != null && !hint.isBlank()) {
				hintsOut.add(hint);
			}
		}

		if (results.isEmpty()) {
			return context;
		}

		StringBuilder sb = new StringBuilder(context == null ? "" : context.trim());

		for (Map.Entry<String, List<String>> entry : results.entrySet()) {
			sb.append("\n\n[Kết quả ").append(entry.getKey()).append(" đã tra sẵn]\n")
					.append(String.join("\n\n", entry.getValue()));
		}

		return sb.toString();
	}

	// =========================================================
	// HƯỚNG DẪN TRẢ LỜI (ScreenModule.answerGuide)
	//
	// Chỉ khi câu hỏi theo tháng đang xem: nhắc tháng khác (đã / không dựng được context) -> null,
	// vì kết luận màn hình tính theo tháng đang xem. Lỗi -> null (log WARN tên lỗi, không log nội dung).
	// =========================================================

	private static AnswerGuide answerGuide(
			ScreenModule module,
			String screenKey,
			String question,
			QuestionMonthDetector.MonthPlan plan,
			Map<String, String> params,
			String lang
	) {

		if (module == null) {
			return null;
		}

		if (plan != null && (!plan.extra().isEmpty() || plan.mismatch())) {
			return null;
		}

		try {
			return module.answerGuide(question, params, lang);
		} catch (RuntimeException e) {
			log.warn("Assistant: cannot build answer guide screenKey={} error={}",
					screenKey, e.getClass().getSimpleName());
			return null;
		}
	}

	// Gợi ý tiếp theo của câu trả lời template (kind = dynamic: theo dữ liệu màn hình)
	private static List<AssistantSuggestion> suggestions(AnswerGuide guide) {
		return guide.suggestions().stream()
				.filter(s -> s != null && !s.isBlank())
				.map(s -> new AssistantSuggestion(s, AssistantSuggestion.DYNAMIC))
				.toList();
	}

	// Tháng chạy pre-fetch: có dựng context tháng được hỏi -> các tháng đó
	//   (+ tháng đang xem nếu câu hỏi cũng nhắc tới, vd so sánh); không -> chỉ tháng đang xem (null)
	private static List<YearMonth> prefetchMonths(QuestionMonthDetector.MonthPlan plan, List<YearMonth> builtMonths) {

		if (plan == null || builtMonths.isEmpty()) {
			return Collections.singletonList(null);
		}

		List<YearMonth> months = new ArrayList<>();
		if (plan.viewedMentioned()) {
			months.add(plan.viewed());
		}
		months.addAll(builtMonths);

		return months;
	}

	// =========================================================
	// THÁNG ĐƯỢC HỎI (màn hình có supportsMonthQuestions)
	//
	// Mỗi tháng trong plan.extra(): dựng context RÚT GỌN (ScreenModule.buildCompactContext),
	// nối sau context chính:
	//   "[Dữ liệu tháng YYYY-MM (được hỏi trong câu hỏi)]\n<context rút gọn>"
	// Module trả null / rỗng (tháng chưa có dữ liệu) -> không thêm khối, đưa vào noDataOut.
	// Lỗi dựng context 1 tháng -> bỏ tháng đó (log WARN, không log nội dung).
	// builtOut: các tháng dựng thành công.
	// =========================================================

	private String appendAskedMonths(
			ScreenModule module,
			String screenKey,
			String context,
			QuestionMonthDetector.MonthPlan plan,
			String lang,
			List<YearMonth> builtOut,
			List<YearMonth> noDataOut
	) {

		if (plan == null || plan.extra().isEmpty() || module == null || !module.supportsMonthQuestions()) {
			return context;
		}

		StringBuilder sb = new StringBuilder(context == null ? "" : context.trim());

		for (YearMonth month : plan.extra()) {
			try {
				String monthContext = module.buildCompactContext(month, lang);

				if (monthContext == null || monthContext.isBlank()) {
					noDataOut.add(month);
					continue;
				}

				sb.append("\n\n[Dữ liệu tháng ").append(month).append(" (được hỏi trong câu hỏi)]\n")
						.append(monthContext.trim());
				builtOut.add(month);
			} catch (RuntimeException e) {
				log.warn("Assistant: cannot build asked-month context screenKey={} error={}",
						screenKey, e.getClass().getSimpleName());
			}
		}

		return sb.toString();
	}

	// Câu hỏi + lưu ý tháng:
	//   đã dựng context tháng được hỏi -> "trả lời theo dữ liệu tháng … đã gửi kèm; câu đầu ghi rõ…"
	//   tháng được hỏi chưa có dữ liệu  -> "tháng … chưa có dữ liệu trên hệ thống; nói rõ điều này."
	//   chỉ nhắc tháng khác, không dựng được và không thuộc 2 trường hợp trên
	//     (ngoài phạm vi / màn khác / lỗi) -> nhắc đổi tháng trên màn hình
	private static String annotateQuestion(
			String question,
			QuestionMonthDetector.MonthPlan plan,
			List<YearMonth> builtMonths,
			List<YearMonth> noDataMonths,
			String lang
	) {

		if (plan == null) {
			return question;
		}

		StringBuilder sb = new StringBuilder(question);

		if (!builtMonths.isEmpty()) {
			sb.append('\n')
					.append(QuestionMonthDetector.answerMonthNote(builtMonths, plan.viewed(), plan.viewedMentioned(), lang));
		}

		for (YearMonth month : noDataMonths) {
			sb.append('\n').append(QuestionMonthDetector.noDataNote(month, lang));
		}

		if (builtMonths.isEmpty() && noDataMonths.isEmpty() && plan.mismatch()) {
			sb.append('\n').append(QuestionMonthDetector.note(plan.mentioned().get(0), plan.viewed(), lang));
		}

		return sb.toString();
	}

	private String runTool(
			AssistantTool tool,
			ToolCall call,
			Map<String, String> screenParams,
			ToolMonthScope monthScope,
			String lang,
			int round,
			Set<AssistantSource> sources,
			AssistantAuditTrace trace
	) {

		long start = System.nanoTime();
		String outcome = "ok";
		// Kết quả trả về (audit: chỉ ghi cho pre-fetch)
		String output = null;

		try {
			if (tool == null) {
				outcome = "unknown";
				return output = "error=unknown_tool|tool=" + call.name();
			}

			Map<String, Object> args = parseArguments(call.arguments());
			// Tham số month (tùy chọn): tháng đang xem hoặc tháng đã dựng context; khác -> invalid_argument
			Map<String, String> effectiveParams = monthScope.resolve(args, screenParams);
			String result = tool.execute(args, effectiveParams, lang);

			sources.add(new AssistantSource(
					call.name(),
					stringArg(args, "div"),
					stringArg(args, "cost"),
					monthScope.monthOf(effectiveParams)
			));

			// Chỉ profile dev: log toàn bộ kết quả tool (chưa cắt) ở DEBUG
			if (log.isDebugEnabled() && isDevProfile()) {
				log.debug("Assistant tool result: name={} round={}\n{}", call.name(), round, result);
			}

			return output = truncateToolResult(result, tool.maxResultLength(args));

		} catch (IllegalArgumentException e) {
			outcome = "invalid_argument";
			// Message của tool dạng key=value (vd "arg=<tên>|allowed=<giá trị hợp lệ>")
			return output = "error=invalid_argument" + (e.getMessage() != null ? "|" + e.getMessage() : "");

		} catch (RuntimeException e) {
			outcome = "error " + e.getClass().getSimpleName();
			log.warn("Assistant: tool failed tool={}", call.name(), e);
			return output = "error=tool_failed";

		} finally {
			trace.tool(call.name(), round, elapsedMs(start), outcome, output);

			// Chỉ log tên tool, vòng, kết quả, thời gian; không log tham số / nội dung
			log.info(
					"Assistant tool: rid={} name={} round={} outcome={} durationMs={}",
					RequestTiming.rid(),
					call.name(),
					round,
					outcome,
					elapsedMs(start)
			);
		}
	}

	private Map<String, Object> parseArguments(String json) {

		if (json == null || json.isBlank()) {
			return Map.of();
		}

		try {
			Map<String, Object> args =
					objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
					});

			return args != null ? args : Map.of();

		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("arg=arguments|expected=json_object");
		}
	}

	// div / cost chuẩn hóa chữ hoa cho sources (giữ field của API response; tool đã validate)
	private static String stringArg(Map<String, Object> args, String key) {

		Object value = args.get(key);

		return value == null || value.toString().isBlank()
				? null
				: value.toString().trim().toUpperCase(Locale.ROOT);
	}

	private boolean isDevProfile() {
		return environment.acceptsProfiles(Profiles.of(DEV_PROFILE));
	}

	// maxLength theo tool (AssistantTool.maxResultLength, mặc định 3000)
	static String truncateToolResult(String result, int maxLength) {

		if (result == null) {
			return "";
		}

		int limit = maxLength > 0 ? maxLength : AssistantTool.DEFAULT_MAX_RESULT_LENGTH;

		return result.length() > limit
				? result.substring(0, limit) + "\n(đã rút gọn)"
				: result;
	}

	private static String answerOrThrow(LlmResponse response) {

		if (response.content() != null && !response.content().isBlank()) {
			return response.content();
		}

		// length: reasoning / câu trả lời dùng hết max_tokens
		if ("length".equals(response.finishReason())) {
			throw new LlmTruncatedException();
		}

		throw new LlmEmptyException(response.finishReason());
	}

	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	// Tổng ký tự content mọi message (chưa gồm mô tả tool / token định dạng của template)
	static int promptChars(List<ChatMessage> messages) {
		int total = 0;
		for (ChatMessage m : messages) {
			total += m.content() != null ? m.content().length() : 0;
		}
		return total;
	}

	// Ước tính token = ký tự / assistant.llm.chars-per-token (≤ 0 -> 3.0)
	private int estimateTokens(int chars) {
		double ratio = assistantProperties.getLlm().getCharsPerToken();
		return (int) Math.ceil(chars / (ratio > 0 ? ratio : 3.0));
	}

	// 1 dòng INFO mỗi bước dựng prompt (không log nội dung); trả về số ms (dùng cho audit)
	private static long logStep(String step, long startNanos) {
		long ms = elapsedMs(startNanos);
		log.info("Assistant step: rid={} step={} durationMs={}", RequestTiming.rid(), step, ms);
		return ms;
	}

	// Mức suy luận thực tế: assistant.llm.reasoning-effort nếu có, không thì theo dòng "Reasoning:" của prompt
	private String reasoningEffort(String question) {

		String configured = assistantProperties.getLlm().getReasoningEffort();

		if (configured != null && !configured.isBlank()) {
			return configured.trim();
		}

		return PromptBuilder.reasoningLine(question).replace("Reasoning:", "").trim();
	}

	private static String abbreviate(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
	}


	// =========================================================
	// VALIDATE
	// =========================================================

	// Bỏ trống -> "vi"
	private static String validateLang(String lang) {

		if (lang == null || lang.isBlank()) {
			return PromptBuilder.LANG_VI;
		}

		String value = lang.trim().toLowerCase(Locale.ROOT);

		if (!PromptBuilder.LANG_VI.equals(value) && !PromptBuilder.LANG_EN.equals(value)) {
			throw new IllegalArgumentException("lang must be \"vi\" or \"en\".");
		}

		return value;
	}

	// Bỏ trống -> chỉ dùng quy tắc chung + core/common.md, không có context
	private static String validateScreenKey(String screenKey) {

		if (screenKey == null || screenKey.isBlank()) {
			return null;
		}

		String key = screenKey.trim();

		if (!SCREEN_KEY.matcher(key).matches()) {
			throw new IllegalArgumentException("screenKey is invalid.");
		}

		return key;
	}

	private static String validateQuestion(String question) {

		if (question == null || question.isBlank()) {
			throw new IllegalArgumentException("question is required.");
		}

		String q = question.trim();

		if (q.length() > MAX_QUESTION_LENGTH) {
			throw new IllegalArgumentException(
					"question must be at most " + MAX_QUESTION_LENGTH + " characters."
			);
		}

		return q;
	}

	private static Map<String, String> validateParams(Map<String, String> params) {

		if (params == null) {
			return Map.of();
		}

		if (params.size() > MAX_PARAMS) {
			throw new IllegalArgumentException("Too many params.");
		}

		for (Map.Entry<String, String> entry : params.entrySet()) {
			if (entry.getValue() != null && entry.getValue().length() > MAX_PARAM_VALUE_LENGTH) {
				throw new IllegalArgumentException("Param value is too long.");
			}
		}

		return params;
	}

	// Chỉ giữ user/assistant (không cho FE chèn "system"), bỏ rỗng, cắt dài, lấy 6 lượt cuối
	static List<ChatMessage> sanitizeHistory(List<ChatMessage> history) {

		if (history == null || history.isEmpty()) {
			return List.of();
		}

		List<ChatMessage> valid = history.stream()
				.filter(m -> m != null && m.content() != null && !m.content().isBlank())
				.filter(m -> ChatMessage.USER.equals(m.role()) || ChatMessage.ASSISTANT.equals(m.role()))
				.map(m -> new ChatMessage(m.role(), truncate(m.content().trim())))
				.toList();

		return valid.size() > MAX_HISTORY
				? valid.subList(valid.size() - MAX_HISTORY, valid.size())
				: valid;
	}

	private static String truncate(String text) {

		return text.length() > MAX_HISTORY_CONTENT_LENGTH
				? text.substring(0, MAX_HISTORY_CONTENT_LENGTH)
				: text;
	}
}
