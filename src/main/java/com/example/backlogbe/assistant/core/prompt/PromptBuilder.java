package com.example.backlogbe.assistant.core.prompt;

import com.example.backlogbe.assistant.core.api.dto.ChatMessage;
import com.example.backlogbe.assistant.core.config.AssistantProperties;
import com.example.backlogbe.assistant.core.screen.ScreenRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// =========================================================
// PROMPT BUILDER (chung cho mọi màn hình)
//
// Thứ tự tối ưu KV cache của LM Studio (prefix giống nhau giữa các request thì không phải tính lại):
// system = PHẦN CỐ ĐỊNH, giống nhau byte-by-byte giữa các request cùng screenKey + lang:
//          câu chỉ định ngôn ngữ + quy tắc chung (CORE_RULES) + rules.md + core/common.md + knowledge.md
//          + câu chỉ định ngôn ngữ, cuối cùng là "Reasoning: low" (cố định mọi câu hỏi để giữ KV cache).
//          Template gpt-oss đặt mô tả tool ngay sau system -> tool cũng nằm trong prefix cố định.
// history
// user   = PHẦN ĐỘNG: "Dữ liệu hiện tại:" + context (snapshot, ngày, ô đang xem, kết quả pre-fetch…)
//          (+ khối kết luận màn hình tính sẵn – ScreenModule.answerGuide, nếu có)
//          + "Câu hỏi:" + câu hỏi (+ questionHints, mỗi câu 1 dòng) (+ "\n(Trả lời bằng tiếng Việt.)" khi lang = vi)
// Không đưa dữ liệu thay đổi theo request (ngày giờ, số liệu…) vào system.
// Văn bản đọc từ classpath, cache khi khởi động; thiếu file thì bỏ qua:
//   assistant/core/common.md
//   assistant/screens/{screenKey}/knowledge.md, rules.md   (screenKey của các ScreenModule đã đăng ký)
// CORE_RULES + rules.md ghép liền nhau (rules.md bỏ khoảng trắng cuối + "\n"), giống khối quy tắc cũ.
// Placeholder {{assistantName}} trong các file .md được thay bằng assistant.name khi nạp.
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBuilder {

	public static final String LANG_VI = "vi";
	public static final String LANG_EN = "en";

	private static final String COMMON_PATH = "assistant/core/common.md";
	private static final String SCREEN_PATH = "assistant/screens/%s/%s";
	private static final String KNOWLEDGE_FILE = "knowledge.md";
	private static final String RULES_FILE = "rules.md";

	// Placeholder tên mascot trong common.md / knowledge.md / rules.md
	private static final String NAME_PLACEHOLDER = "{{assistantName}}";

	// Dòng đầu và dòng cuối system prompt (model nhỏ dễ trôi ngôn ngữ theo nhãn dữ liệu / tool)
	private static final String LANG_LINE_VI = "Luôn trả lời bằng tiếng Việt.";
	private static final String LANG_LINE_EN = "Always answer in English.";

	// Nối vào cuối câu hỏi khi lang = vi
	private static final String QUESTION_SUFFIX_VI = "\n(Trả lời bằng tiếng Việt.)";

	// gpt-oss đọc mức suy luận từ system prompt (harmony format): "Reasoning: low|medium|high"
	private static final String REASONING_LOW = "Reasoning: low";

	// Quy tắc chung mọi màn hình. Quy tắc riêng màn hình: screens/{screenKey}/rules.md
	private static final String CORE_RULES = """
			Bạn là trợ lý (mascot) của hệ thống quản lý nhà máy. Quy tắc bắt buộc:
			- Trả lời ngắn gọn, đi thẳng vào ý chính.
			- Câu hỏi đơn giản (hỏi 1 con số, 1 định nghĩa): trả lời 1–2 câu, không tiêu đề, không gạch đầu dòng.
			- Câu hỏi đánh giá/so sánh: tối đa 5 gạch đầu dòng ngắn, chỉ in đậm con số chính.
			- Không dùng tiêu đề (#), không lặp lại câu hỏi.
			- Câu hỏi có/không: câu đầu trả lời "Được" hoặc "Không", rồi giải thích ngắn.
			- Tuyệt đối không dùng LaTeX hay ký hiệu toán ($ bao quanh công thức, \\[, \\(, \\text, \\frac, \\times); viết công thức dạng text, dùng ×, /, +, −.
			""";

	private final ScreenRegistry screenRegistry;
	private final AssistantProperties assistantProperties;

	private String common;
	private final Map<String, String> knowledge = new HashMap<>();
	private final Map<String, String> rules = new HashMap<>();

	@PostConstruct
	void loadTexts() throws IOException {

		common = read(COMMON_PATH);

		for (String screenKey : screenRegistry.screenKeys()) {

			String k = read(SCREEN_PATH.formatted(screenKey, KNOWLEDGE_FILE));
			if (k != null) {
				knowledge.put(screenKey, k);
			}

			String r = read(SCREEN_PATH.formatted(screenKey, RULES_FILE));
			if (r != null) {
				rules.put(screenKey, r);
			}
		}

		log.info("Assistant prompt texts loaded: common={} knowledge={} rules={}",
				common != null, knowledge.keySet(), rules.keySet());
	}

	// Đọc UTF-8, đổi CRLF -> LF, trim, thay {{assistantName}}; không có file / rỗng -> null
	private String read(String path) throws IOException {

		ClassPathResource resource = new ClassPathResource(path);

		if (!resource.exists()) {
			return null;
		}

		try (InputStream in = resource.getInputStream()) {
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8)
					.replace("\r\n", "\n")
					.replace(NAME_PLACEHOLDER, assistantProperties.resolvedName())
					.trim();
			return text.isEmpty() ? null : text;
		}
	}

	/**
	 * @param history       đã lọc (chỉ user/assistant, tối đa 6 lượt)
	 * @param question      câu hỏi (có thể đã nối lưu ý tháng)
	 * @param lang          "vi" | "en" (đã validate)
	 * @param questionHints câu nhắc nối vào cuối câu hỏi, mỗi câu 1 dòng (vd fallbackHint của PrefetchRule)
	 */
	public List<ChatMessage> build(
			String screenKey,
			String context,
			List<ChatMessage> history,
			String question,
			String lang,
			List<String> questionHints
	) {
		return build(screenKey, context, history, question, lang, questionHints, null);
	}

	/**
	 * @param conclusion khối kết luận màn hình tính sẵn (AnswerGuide.conclusion), đặt sau context,
	 *                   ngay trước "Câu hỏi:"; null / rỗng = không có
	 */
	public List<ChatMessage> build(
			String screenKey,
			String context,
			List<ChatMessage> history,
			String question,
			String lang,
			List<String> questionHints,
			String conclusion
	) {

		List<ChatMessage> messages = new ArrayList<>();

		messages.add(ChatMessage.system(buildSystem(screenKey, lang, reasoningLine(question))));
		messages.addAll(history);
		messages.add(ChatMessage.user(buildUser(context, question, lang, questionHints, conclusion)));

		return messages;
	}

	// Phần cố định (chỉ phụ thuộc screenKey, lang) + dòng Reasoning ở cuối
	String buildSystem(
			String screenKey,
			String lang,
			String reasoningLine
	) {

		String langLine = LANG_EN.equals(lang) ? LANG_LINE_EN : LANG_LINE_VI;

		StringBuilder sb = new StringBuilder(langLine)
				.append("\n\n").append(CORE_RULES);

		String screenRules = screenKey != null ? rules.get(screenKey) : null;
		if (screenRules != null) {
			sb.append(screenRules).append('\n');
		}

		appendSection(sb, common);

		if (screenKey != null) {
			appendSection(sb, knowledge.get(screenKey));
		}

		sb.append("\n\n").append(langLine)
				.append("\n\n").append(reasoningLine);

		return sb.toString();
	}

	// Phần động: dữ liệu màn hình (+ khối kết luận) + câu hỏi
	static String buildUser(
			String context,
			String question,
			String lang,
			List<String> questionHints,
			String conclusion
	) {

		boolean en = LANG_EN.equals(lang);

		StringBuilder sb = new StringBuilder("Dữ liệu hiện tại:\n")
				.append(context == null || context.isBlank()
						? "(không có dữ liệu)"
						: context.trim());

		// Đặt sát câu hỏi: model nhỏ chú ý phần cuối prompt nhiều nhất
		if (conclusion != null && !conclusion.isBlank()) {
			sb.append("\n\n").append(conclusion.trim());
		}

		sb.append("\n\n").append(en ? "Question: " : "Câu hỏi: ")
				.append(question);

		for (String hint : questionHints) {
			if (hint != null && !hint.isBlank()) {
				sb.append('\n').append(hint);
			}
		}

		if (!en) {
			sb.append(QUESTION_SUFFIX_VI);
		}

		return sb.toString();
	}

	// Cố định "Reasoning: low" cho mọi câu hỏi: dòng này nằm trong system prompt, đổi theo câu hỏi sẽ làm mất KV cache
	// (prefix khác nhau). Kết luận khó đã do code tính sẵn (Assessment / AnswerGuide). Giữ tham số để không đổi API.
	public static String reasoningLine(String question) {
		return REASONING_LOW;
	}

	private static void appendSection(StringBuilder sb, String text) {

		if (text != null && !text.isBlank()) {
			sb.append("\n\n").append(text);
		}
	}
}
