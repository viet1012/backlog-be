package com.example.backlogbe.assistant.core.postprocess;

import com.example.backlogbe.assistant.core.prompt.PromptBuilder;
import com.example.backlogbe.assistant.core.screen.ScreenRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// =========================================================
// HẬU XỬ LÝ CÂU TRẢ LỜI (chung cho mọi màn hình)
//
// 1. Thay mã nội bộ còn sót (chốt chặn nếu model vẫn in mã ra) theo
//    resources/assistant/screens/{screenKey}/codes.json:
//      { "CODE": { "vi": "nhãn", "en": "label" }, ... }   (thứ tự trong file = thứ tự thay)
//    Đọc khi khởi động cho các screenKey đã đăng ký; thiếu file thì bỏ qua.
// 0. Chuẩn hoá gạch nối / khoảng trắng Unicode (normalizeTypography, mọi lang).
// 2. lang = vi: dấu phẩy thập phân -> dấu chấm (normalizeDecimalComma).
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerPostProcessor {

	private static final String CODES_PATH = "assistant/screens/%s/codes.json";

	private final ScreenRegistry screenRegistry;
	private final ObjectMapper objectMapper;

	// screenKey -> (mã -> {vi, en})
	private final Map<String, LinkedHashMap<String, Map<String, String>>> codes = new HashMap<>();

	@PostConstruct
	void loadCodes() throws IOException {

		for (String screenKey : screenRegistry.screenKeys()) {

			ClassPathResource resource = new ClassPathResource(CODES_PATH.formatted(screenKey));

			if (!resource.exists()) {
				continue;
			}

			try (InputStream in = resource.getInputStream()) {
				LinkedHashMap<String, Map<String, String>> map = objectMapper.readValue(in,
						new TypeReference<LinkedHashMap<String, Map<String, String>>>() {
						});
				if (map != null) {
					codes.put(screenKey, map);
				}
			}
		}

		log.info("Assistant answer codes loaded: {}", codes.keySet());
	}

	public String process(String answer, String screenKey, String lang) {

		String result = normalizeTypography(answer);

		result = replaceInternalCodes(result, labels(screenKey, lang), screenKey);

		return PromptBuilder.LANG_VI.equals(lang) ? normalizeDecimalComma(result) : result;
	}


	// =========================================================
	// CHUẨN HOÁ KÝ TỰ (mọi lang)
	//
	// Model (gpt-oss) hay in gạch nối / khoảng trắng Unicode làm hỏng mã vật tư khi copy:
	//   "MDC‑25MX" (U+2011) -> "MDC-25MX", "2026‑11" -> "2026-11"
	//   U+2010 / U+2011 (hyphen, non-breaking hyphen) -> "-"
	//   U+00A0 / U+202F / U+2007 (khoảng trắng không ngắt) -> " "
	//   "6.8 %" -> "6.8%"
	// Giữ nguyên "–" (U+2013, khoảng ngày "1–8") và "−" (U+2212, dấu trừ trong công thức).
	// =========================================================

	private static final Pattern UNICODE_HYPHEN = Pattern.compile("[\\u2010\\u2011]");
	private static final Pattern UNICODE_SPACE = Pattern.compile("[\\u00A0\\u202F\\u2007]");
	private static final Pattern SPACE_BEFORE_PERCENT = Pattern.compile("(\\d) +%");

	static String normalizeTypography(String answer) {

		if (answer == null || answer.isEmpty()) {
			return answer;
		}

		String result = UNICODE_HYPHEN.matcher(answer).replaceAll("-");
		result = UNICODE_SPACE.matcher(result).replaceAll(" ");

		return SPACE_BEFORE_PERCENT.matcher(result).replaceAll("$1%");
	}

	// Mã -> nhãn theo lang (en thiếu thì dùng vi); không có file -> rỗng
	Map<String, String> labels(String screenKey, String lang) {

		LinkedHashMap<String, Map<String, String>> map = screenKey != null ? codes.get(screenKey) : null;

		if (map == null) {
			return Map.of();
		}

		Map<String, String> labels = new LinkedHashMap<>();

		for (Map.Entry<String, Map<String, String>> entry : map.entrySet()) {

			Map<String, String> byLang = entry.getValue();

			if (byLang == null) {
				continue;
			}

			String label = PromptBuilder.LANG_EN.equals(lang) && byLang.get(PromptBuilder.LANG_EN) != null
					? byLang.get(PromptBuilder.LANG_EN)
					: byLang.get(PromptBuilder.LANG_VI);

			if (label != null) {
				labels.put(entry.getKey(), label);
			}
		}

		return labels;
	}


	// =========================================================
	// THAY MÃ NỘI BỘ CÒN SÓT
	//
	// Mã đứng riêng (không dính chữ / số / "_" hai bên), phân biệt hoa thường.
	// Log WARN tên các mã bị thay (không log nội dung câu trả lời).
	// =========================================================

	static String replaceInternalCodes(String answer, Map<String, String> labels, String screenKey) {

		if (answer == null || answer.isEmpty() || labels.isEmpty()) {
			return answer;
		}

		String result = answer;
		List<String> replaced = new ArrayList<>();

		for (Map.Entry<String, String> entry : labels.entrySet()) {

			Pattern pattern = Pattern.compile(
					"(?<![\\p{L}\\p{N}_])" + Pattern.quote(entry.getKey()) + "(?![\\p{L}\\p{N}_])"
			);

			Matcher matcher = pattern.matcher(result);

			if (matcher.find()) {
				replaced.add(entry.getKey());
				result = matcher.replaceAll(Matcher.quoteReplacement(entry.getValue()));
			}
		}

		if (!replaced.isEmpty()) {
			log.warn("Assistant: replaced internal codes in answer screenKey={} codes={}", screenKey, replaced);
		}

		return result;
	}


	// =========================================================
	// DẤU PHẨY THẬP PHÂN -> DẤU CHẤM (lang = vi)
	//
	// Chỉ cụm "chữ số,chữ số" có đúng 1 dấu phẩy, không dính số / dấu phẩy / dấu chấm hai bên:
	//   "5,5" -> "5.5", "175,2" -> "175.2", "0,500" -> "0.500"
	// Giữ nguyên:
	//   "1,234,567" (nhiều nhóm hàng nghìn)
	//   "1,234" / "175,234" (1–3 chữ số + đúng 3 chữ số, không bắt đầu bằng 0: coi là phân cách hàng nghìn)
	//   "5, 6" (có khoảng trắng sau dấu phẩy)
	//   "ngày 5,6" / "ngay 5,6" / "day 5,6" / "days 5,6" (danh sách ngày; bỏ qua khoảng trắng giữa từ và số)
	// =========================================================

	private static final Pattern DECIMAL_COMMA = Pattern.compile("(?<![\\d,.])(\\d+),(\\d+)(?![\\d,])");

	static String normalizeDecimalComma(String answer) {

		if (answer == null || answer.indexOf(',') < 0) {
			return answer;
		}

		Matcher matcher = DECIMAL_COMMA.matcher(answer);
		StringBuilder sb = new StringBuilder();

		while (matcher.find()) {

			String integer = matcher.group(1);
			String fraction = matcher.group(2);

			boolean thousands = integer.length() <= 3 && fraction.length() == 3 && integer.charAt(0) != '0';
			boolean dayList = afterDayWord(answer, matcher.start());

			matcher.appendReplacement(sb, Matcher.quoteReplacement(
					thousands || dayList ? matcher.group() : integer + "." + fraction
			));
		}

		matcher.appendTail(sb);

		return sb.toString();
	}

	private static final List<String> DAY_WORDS = List.of("ngày", "ngay", "days", "day");

	// Ngay trước vị trí pos (bỏ khoảng trắng) là từ "ngày" / "ngay" / "day" / "days" đứng riêng
	private static boolean afterDayWord(String text, int pos) {

		int end = pos;
		while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
			end--;
		}

		String before = text.substring(0, end).toLowerCase(Locale.ROOT);

		for (String word : DAY_WORDS) {
			if (before.endsWith(word)) {
				int start = before.length() - word.length();
				if (start == 0 || !Character.isLetterOrDigit(before.charAt(start - 1))) {
					return true;
				}
			}
		}

		return false;
	}
}
