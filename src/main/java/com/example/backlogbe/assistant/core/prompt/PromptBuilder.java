package com.example.backlogbe.assistant.core.prompt;

import com.example.backlogbe.assistant.core.api.dto.ChatMessage;
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
import java.util.Locale;
import java.util.Map;

// =========================================================
// PROMPT BUILDER (chung cho mọi màn hình)
//
// system = câu chỉ định ngôn ngữ + "Reasoning: low" (hoặc medium cho câu hỏi đánh giá / phân tích /
//          vì sao / so sánh) + quy tắc chung (CORE_RULES) + rules.md của màn hình
//          + core/common.md + knowledge.md của màn hình
//          + "Dữ liệu hiện tại:" + context + câu chỉ định ngôn ngữ
// user   = câu hỏi (+ các câu nhắc questionHints, mỗi câu 1 dòng)
//          (+ "\n(Trả lời bằng tiếng Việt.)" khi lang = vi)
// Văn bản đọc từ classpath, cache khi khởi động; thiếu file thì bỏ qua:
//   assistant/core/common.md
//   assistant/screens/{screenKey}/knowledge.md, rules.md   (screenKey của các ScreenModule đã đăng ký)
// CORE_RULES + rules.md ghép liền nhau (rules.md bỏ khoảng trắng cuối + "\n"), giống khối quy tắc cũ.
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

	// Dòng đầu và dòng cuối system prompt (model nhỏ dễ trôi ngôn ngữ theo nhãn dữ liệu / tool)
	private static final String LANG_LINE_VI = "Luôn trả lời bằng tiếng Việt.";
	private static final String LANG_LINE_EN = "Always answer in English.";

	// Nối vào cuối câu hỏi khi lang = vi
	private static final String QUESTION_SUFFIX_VI = "\n(Trả lời bằng tiếng Việt.)";

	// gpt-oss đọc mức suy luận từ system prompt (harmony format): "Reasoning: low|medium|high"
	private static final String REASONING_LOW = "Reasoning: low";
	private static final String REASONING_MEDIUM = "Reasoning: medium";

	// Câu hỏi chứa các cụm này (có dấu hoặc không dấu) -> Reasoning: medium cho lượt đó
	private static final List<String> MEDIUM_REASONING_KEYWORDS = List.of(
			"đánh giá", "danh gia",
			"phân tích", "phan tich",
			"vì sao", "vi sao",
			"so sánh", "so sanh"
	);

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

	// Đọc UTF-8, đổi CRLF -> LF, trim; không có file / rỗng -> null
	private static String read(String path) throws IOException {

		ClassPathResource resource = new ClassPathResource(path);

		if (!resource.exists()) {
			return null;
		}

		try (InputStream in = resource.getInputStream()) {
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8)
					.replace("\r\n", "\n")
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

		List<ChatMessage> messages = new ArrayList<>();

		messages.add(ChatMessage.system(buildSystem(screenKey, context, lang, reasoningLine(question))));
		messages.addAll(history);

		StringBuilder userText = new StringBuilder(question);
		for (String hint : questionHints) {
			if (hint != null && !hint.isBlank()) {
				userText.append('\n').append(hint);
			}
		}

		messages.add(ChatMessage.user(
				LANG_EN.equals(lang) ? userText.toString() : userText + QUESTION_SUFFIX_VI
		));

		return messages;
	}

	String buildSystem(
			String screenKey,
			String context,
			String lang,
			String reasoningLine
	) {

		String langLine = LANG_EN.equals(lang) ? LANG_LINE_EN : LANG_LINE_VI;

		StringBuilder sb = new StringBuilder(langLine)
				.append("\n\n").append(reasoningLine)
				.append("\n\n").append(CORE_RULES);

		String screenRules = screenKey != null ? rules.get(screenKey) : null;
		if (screenRules != null) {
			sb.append(screenRules).append('\n');
		}

		appendSection(sb, common);

		if (screenKey != null) {
			appendSection(sb, knowledge.get(screenKey));
		}

		sb.append("\n\nDữ liệu hiện tại:\n")
				.append(context == null || context.isBlank()
						? "(không có dữ liệu)"
						: context.trim());

		sb.append("\n\n").append(langLine);

		return sb.toString();
	}

	// Câu hỏi đánh giá / phân tích / vì sao / so sánh -> medium; còn lại low
	static String reasoningLine(String question) {

		if (question == null) {
			return REASONING_LOW;
		}

		String q = question.toLowerCase(Locale.ROOT);

		for (String keyword : MEDIUM_REASONING_KEYWORDS) {
			if (q.contains(keyword)) {
				return REASONING_MEDIUM;
			}
		}

		return REASONING_LOW;
	}

	private static void appendSection(StringBuilder sb, String text) {

		if (text != null && !text.isBlank()) {
			sb.append("\n\n").append(text);
		}
	}
}
