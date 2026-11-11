package com.example.backlogbe.assistant.core.chat;

import com.example.backlogbe.assistant.core.prompt.PromptBuilder;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// =========================================================
// CHỐT CHẶN CHUNG (không chứa từ khóa nghiệp vụ)
//
// isPromptExtraction : câu hỏi đòi in / bỏ qua hướng dẫn hệ thống -> trả lời từ chối ngay, không gọi LLM
//                      (audit 2026-11-10: model in nguyên system prompt, 65 s, finish_reason=length).
// leaksSystemPrompt  : câu trả lời chứa nguyên văn ≥ LEAK_MIN_LINES dòng dài (≥ LEAK_MIN_LINE_LENGTH ký tự)
//                      của system prompt -> thay bằng câu từ chối.
// effectiveLang      : lang = vi nhưng câu hỏi rõ ràng là tiếng Anh (không có chữ có dấu tiếng Việt,
//                      ≥ 2 từ chức năng tiếng Anh) -> "en" (audit: "Which machine spends the most…" trả lời tiếng Việt).
// =========================================================
final class PromptGuard {

	private PromptGuard() {
	}

	static final int LEAK_MIN_LINES = 2;
	static final int LEAK_MIN_LINE_LENGTH = 40;

	// Trên câu đã fold (không dấu, chữ thường)
	private static final Pattern EXTRACTION = Pattern.compile(
			"system\\s*prompt|prompt\\s+he\\s+thong|developer\\s+message"
					+ "|bo\\s+qua\\s+(?:\\S+\\s+){0,3}?(?:huong\\s+dan|chi\\s+dan|quy\\s+tac|lenh)"
					+ "|(?:in|hien|xuat|cho\\s+(?:toi|minh|xem))\\s+(?:ra\\s+)?(?:toan\\s+bo\\s+|tat\\s+ca\\s+)?"
					+ "(?:huong\\s+dan|chi\\s+dan|quy\\s+tac)\\s+(?:he\\s+thong|noi\\s+bo|cua\\s+ban|ban\\s+dau)"
					+ "|ignore\\s+(?:\\S+\\s+){0,3}?(?:instructions|rules|prompt)"
					+ "|(?:reveal|print|show|repeat)\\s+(?:me\\s+)?(?:your|the)\\s+(?:\\S+\\s+)?(?:instructions|rules|prompt)"
					// Thông tin xác thực / cấu hình hệ thống (test 2026-11-10 ext-28: qua LLM 15 s)
					+ "|api\\s*key|access\\s*token|secret\\s*key|private\\s*key|mat\\s*khau|password|passwd"
					+ "|connection\\s*string|chuoi\\s*ket\\s*noi"
					+ "|cau\\s*hinh\\s+(?:ket\\s*noi|he\\s*thong|server|may\\s*chu|database|co\\s*so\\s*du\\s*lieu|llm|model)"
					+ "|(?:system|server|database|llm)\\s+(?:config|configuration|credentials)"
	);

	// Chữ cái chỉ có trong tiếng Việt (sau NFC)
	private static final Pattern VIETNAMESE_LETTER = Pattern.compile(
			"[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]");

	private static final List<String> ENGLISH_WORDS = List.of(
			"what", "which", "why", "how", "when", "where", "who", "is", "are", "was", "the", "does", "do",
			"did", "will", "most", "spends", "spend", "spent", "show", "list", "this", "month", "much", "many",
			"can", "please", "of", "in", "for", "and", "than",
			// test 2026-11-11 (1303): "Compare … with …", "What if … cuts … by 15%", "What should I watch today?",
			// "Which frames should I report to …", "Last 3 months … over …", "Export to PDF" bị trả lời tiếng Việt
			"should", "would", "could", "if", "with", "by", "to", "compare", "last", "months", "over", "export",
			"today", "give", "me", "my", "summarize", "summarise", "explain", "tell", "there", "any", "has", "have"
	);

	// Token điều khiển của model (harmony / chat template) lọt vào content, vd "commentary to=functions?",
	// "<|channel|>analysis" (audit 2026-11-10). Chỉ xét câu ngắn để không bắt nhầm câu trả lời thật.
	private static final Pattern CONTROL_TEXT = Pattern.compile(
			"<\\|[a-z_]+\\|>|(?:^|\\s)to=functions|^\\s*(?:analysis|commentary|final)\\s*(?:to=|<\\||$)",
			Pattern.CASE_INSENSITIVE);
	static final int CONTROL_TEXT_MAX_LENGTH = 300;

	static boolean isControlText(String content) {
		return content != null && !content.isBlank()
				&& content.length() <= CONTROL_TEXT_MAX_LENGTH
				&& CONTROL_TEXT.matcher(content.trim()).find();
	}

	// Yêu cầu tạo nội dung ngoài phạm vi trợ lý màn hình (email, đơn, bài viết, dịch, code) – test 2026-11-10:
	// "Viết giúp mình email xin nghỉ phép" -> model viết luôn email (29 s). Trên câu đã fold.
	private static final Pattern OFF_TOPIC_TASK = Pattern.compile(
			// không nhận "làm" ("làm thử" bỏ dấu = "lam thu" trùng "thư")
			"(?:^|\\s)(?:viet|soan)\\s+(?:giup|ho|cho)?\\s*(?:\\S+\\s+){0,2}?"
					+ "(?:email|e-mail|mail|thu|don|bai|van ban|code|doan van|bai tho|cv|hop dong)(?:\\s|$|[?.!,])"
					+ "|(?:^|\\s)dich\\s+(?:giup\\s+|ho\\s+)?(?:\\S+\\s+){0,3}?(?:sang|qua)\\s+(?:tieng|tieng anh|tieng nhat)"
					+ "|(?:^|\\s)(?:write|draft|compose)\\s+(?:me\\s+)?(?:an?\\s+)?(?:email|letter|essay|poem|code|story)"
					+ "|(?:^|\\s)translate\\s+");

	static boolean isOffTopicTask(String question) {
		return question != null && OFF_TOPIC_TASK.matcher(fold(question)).find();
	}

	static String offTopicRefusal(String lang) {
		return PromptBuilder.LANG_EN.equals(lang)
				? "Sorry, I only answer questions about the data on this screen, so I can't help with that. "
				+ "You can ask about the figures, trends or causes of variance shown here."
				: "Xin lỗi, mình chỉ trả lời câu hỏi về dữ liệu trên màn hình này nên không hỗ trợ việc đó. "
				+ "Bạn có thể hỏi về số liệu, xu hướng hoặc nguyên nhân chênh lệch đang hiển thị.";
	}

	static boolean isPromptExtraction(String question) {
		return question != null && EXTRACTION.matcher(fold(question)).find();
	}

	static boolean leaksSystemPrompt(String answer, String systemPrompt) {

		if (answer == null || systemPrompt == null) {
			return false;
		}

		String a = squash(answer);
		int hits = 0;

		for (String line : systemPrompt.split("\n")) {
			String l = squash(line);
			if (l.length() >= LEAK_MIN_LINE_LENGTH && a.contains(l) && ++hits >= LEAK_MIN_LINES) {
				return true;
			}
		}

		return false;
	}

	static String refusal(String lang) {
		return PromptBuilder.LANG_EN.equals(lang)
				? "Sorry, I can't share my internal instructions or configuration. "
				+ "Please ask about the data on this screen."
				: "Xin lỗi, mình không thể chia sẻ hướng dẫn hay cấu hình nội bộ. "
				+ "Bạn có thể hỏi về số liệu trên màn hình này.";
	}

	static String effectiveLang(String question, String lang) {

		if (!PromptBuilder.LANG_VI.equals(lang) || question == null) {
			return lang;
		}

		String q = Normalizer.normalize(question, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

		if (VIETNAMESE_LETTER.matcher(q).find()) {
			return lang;
		}

		int english = 0;
		for (String token : q.split("[^\\p{L}]+")) {
			if (ENGLISH_WORDS.contains(token) && ++english >= 2) {
				return PromptBuilder.LANG_EN;
			}
		}

		return lang;
	}

	private static String fold(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.replace('đ', 'd').replace('Đ', 'D')
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ");
	}

	// Bỏ markdown / khoảng trắng để so nguyên văn (model hay thêm ** hoặc đổi xuống dòng)
	private static String squash(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFC)
				.replaceAll("[*_`#>\\-•]", "")
				.replaceAll("\\s+", " ")
				.trim();
	}
}
