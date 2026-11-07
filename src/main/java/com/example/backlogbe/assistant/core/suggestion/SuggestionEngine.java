package com.example.backlogbe.assistant.core.suggestion;

import com.example.backlogbe.assistant.core.api.dto.AssistantSuggestion;
import com.example.backlogbe.assistant.core.api.dto.AssistantSuggestionsResponse;
import com.example.backlogbe.assistant.core.screen.ScreenModule;
import com.example.backlogbe.assistant.core.screen.ScreenRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

// =========================================================
// GỢI Ý CÂU HỎI CHO MASCOT
//
// File: classpath:assistant/screens/{screenKey}/suggestions.json (screenKey của các ScreenModule đã đăng ký),
//       đọc và cache khi khởi động; thiếu file thì bỏ qua.
// 1. Câu động (tối đa 2): điều kiện lấy từ ScreenModule.suggestionConditions của màn hình,
//    sắp theo tier (nhỏ trước) rồi weight (lớn trước); mỗi khung tối đa 1 câu;
//    mỗi điều kiện chọn ngẫu nhiên 1 mẫu cùng "when", điền {div} / {cost}.
// 2. Phần còn lại: câu tĩnh ngẫu nhiên, mỗi câu từ một nhóm khác nhau.
// exclude: câu (tĩnh, hoặc động sau khi điền) trùng exclude thì bỏ, chọn mẫu / câu khác
//          cùng điều kiện / nhóm; so sánh sau chuẩn hóa (trim, lowercase, bỏ dấu, gộp khoảng trắng).
// Greeting: ScreenModule.suggestionGreeting(điều kiện, lang); không có module / điều kiện -> null.
// Lấy điều kiện lỗi (vd DB) -> greeting null, chỉ trả câu tĩnh, log WARN; tham số sai (vd month) -> 400.
// screenKey không có file -> suggestions rỗng.
// =========================================================
@Slf4j
@Service
public class SuggestionEngine {

	private static final String SUGGESTION_PATH = "assistant/screens/%s/suggestions.json";
	private static final Pattern SCREEN_KEY = Pattern.compile("^[a-z0-9-]{1,50}$");

	static final int DEFAULT_COUNT = 4;
	static final int MAX_COUNT = 10;
	static final int MAX_DYNAMIC = 2;
	static final int MAX_EXCLUDE = 100;

	private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final ObjectMapper objectMapper;
	private final ScreenRegistry screenRegistry;
	private final Map<String, SuggestionFile> files = new HashMap<>();

	public SuggestionEngine(ObjectMapper objectMapper, ScreenRegistry screenRegistry) {
		this.objectMapper = objectMapper;
		this.screenRegistry = screenRegistry;
	}

	@PostConstruct
	void loadFiles() throws IOException {

		for (String screenKey : screenRegistry.screenKeys()) {

			ClassPathResource resource = new ClassPathResource(SUGGESTION_PATH.formatted(screenKey));

			if (!resource.exists()) {
				continue;
			}

			try (InputStream in = resource.getInputStream()) {
				files.put(screenKey, objectMapper.readValue(in, SuggestionFile.class));
			}
		}

		log.info("Assistant suggestions loaded: {}", files.keySet());
	}

	/**
	 * @param params tham số màn hình (theo ScreenModule.paramSpec)
	 * @param lang   "vi" | "en" (bỏ trống = vi)
	 * @param count   số câu (bỏ trống = 4, tối đa 10)
	 * @param exclude các câu không muốn lặp lại (vd câu vừa hỏi), có thể null
	 */
	public AssistantSuggestionsResponse suggest(
			String screenKey,
			Map<String, String> params,
			String lang,
			Integer count,
			List<String> exclude
	) {

		String key = validateScreenKey(screenKey);
		String safeLang = validateLang(lang);
		int safeCount = count == null ? DEFAULT_COUNT : Math.max(1, Math.min(MAX_COUNT, count));

		SuggestionFile file = files.get(key);
		ScreenModule module = screenRegistry.find(key);
		screenRegistry.checkParams(key, params);

		// Tính điều kiện 1 lần, dùng cho cả greeting và câu động; null = không có / lỗi dữ liệu
		List<SuggestionCondition> conditions = module != null ? loadConditions(module, key, params) : null;

		String greeting = conditions != null ? module.suggestionGreeting(conditions, safeLang) : null;

		if (file == null) {
			return new AssistantSuggestionsResponse(greeting, List.of());
		}

		List<AssistantSuggestion> result = new ArrayList<>();
		// Khóa đã chuẩn hóa: câu bị exclude + câu đã chọn (tránh trùng)
		Set<String> usedTexts = excludeKeys(exclude);

		if (conditions != null) {
			addDynamic(result, usedTexts, file, conditions, safeLang, Math.min(MAX_DYNAMIC, safeCount));
		}
		addStatic(result, usedTexts, file, safeLang, safeCount);

		return new AssistantSuggestionsResponse(greeting, result);
	}

	// Module không có câu động (trả null) -> null; tham số sai (vd month) -> ném lại (400);
	// lỗi khác (vd DB) -> null, log WARN
	private static List<SuggestionCondition> loadConditions(
			ScreenModule module,
			String screenKey,
			Map<String, String> params
	) {

		try {
			List<SuggestionCondition> conditions = module.suggestionConditions(params);
			return conditions != null ? new ArrayList<>(conditions) : null;
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("Assistant suggestions: cannot evaluate conditions screenKey={} error={}",
					screenKey, e.getClass().getSimpleName());
			return null;
		}
	}


	// =========================================================
	// CÂU ĐỘNG
	// =========================================================

	private void addDynamic(
			List<AssistantSuggestion> result,
			Set<String> usedTexts,
			SuggestionFile file,
			List<SuggestionCondition> allConditions,
			String lang,
			int limit
	) {

		if (file.dynamic() == null || file.dynamic().isEmpty()) {
			return;
		}

		List<SuggestionCondition> conditions = new ArrayList<>(allConditions);

		conditions.sort(Comparator
				.comparingInt(SuggestionCondition::tier)
				.thenComparing(Comparator.comparingDouble(SuggestionCondition::weight).reversed()));

		Set<String> usedFrames = new HashSet<>();
		int added = 0;

		for (SuggestionCondition condition : conditions) {

			if (added >= limit) {
				break;
			}

			if (usedFrames.contains(condition.frameKey())) {
				continue;
			}

			List<SuggestionFile.DynamicTemplate> templates = new ArrayList<>(file.dynamic().stream()
					.filter(tpl -> condition.when().equals(tpl.when()))
					.toList());

			Collections.shuffle(templates, ThreadLocalRandom.current());

			// Mẫu ngẫu nhiên; trùng exclude / đã dùng thì thử mẫu khác cùng "when"
			String text = null;

			for (SuggestionFile.DynamicTemplate template : templates) {

				String candidate = fill(pick(template.vi(), template.en(), lang), condition);

				if (!candidate.isBlank() && usedTexts.add(normalize(candidate))) {
					text = candidate;
					break;
				}
			}

			if (text == null) {
				continue;
			}

			result.add(new AssistantSuggestion(text, AssistantSuggestion.DYNAMIC));
			usedFrames.add(condition.frameKey());
			added++;
		}
	}

	private static String fill(String template, SuggestionCondition condition) {

		if (template == null) {
			return "";
		}

		return template
				.replace("{div}", condition.div() != null ? condition.div() : "")
				.replace("{cost}", condition.cost() != null ? condition.cost() : "")
				.replaceAll("\\s{2,}", " ")
				.trim();
	}


	// =========================================================
	// CÂU TĨNH
	// =========================================================

	// Mỗi câu từ 1 nhóm khác nhau, thứ tự nhóm ngẫu nhiên
	private static void addStatic(
			List<AssistantSuggestion> result,
			Set<String> usedTexts,
			SuggestionFile file,
			String lang,
			int count
	) {

		if (file.staticGroups() == null || file.staticGroups().isEmpty()) {
			return;
		}

		List<String> groups = new ArrayList<>(file.staticGroups().keySet());
		Collections.shuffle(groups, ThreadLocalRandom.current());

		for (String group : groups) {

			if (result.size() >= count) {
				break;
			}

			List<SuggestionFile.StaticText> items = file.staticGroups().get(group);

			if (items == null || items.isEmpty()) {
				continue;
			}

			List<SuggestionFile.StaticText> shuffled = new ArrayList<>(items);
			Collections.shuffle(shuffled, ThreadLocalRandom.current());

			// Câu ngẫu nhiên trong nhóm; trùng exclude / đã dùng thì thử câu khác cùng nhóm,
			// hết câu thì sang nhóm tiếp theo
			for (SuggestionFile.StaticText item : shuffled) {

				String text = pick(item.vi(), item.en(), lang);

				if (text != null && !text.isBlank() && usedTexts.add(normalize(text))) {
					result.add(new AssistantSuggestion(text.trim(), AssistantSuggestion.STATIC));
					break;
				}
			}
		}
	}


	// =========================================================
	// EXCLUDE / CHUẨN HÓA
	// =========================================================

	private static Set<String> excludeKeys(List<String> exclude) {

		Set<String> keys = new HashSet<>();

		if (exclude == null) {
			return keys;
		}

		exclude.stream()
				.limit(MAX_EXCLUDE)
				.map(SuggestionEngine::normalize)
				.filter(key -> !key.isEmpty())
				.forEach(keys::add);

		return keys;
	}

	// trim, lowercase, bỏ dấu tiếng Việt (gồm đ), gộp khoảng trắng
	static String normalize(String text) {

		if (text == null) {
			return "";
		}

		String noMarks = COMBINING_MARKS
				.matcher(Normalizer.normalize(text, Normalizer.Form.NFD))
				.replaceAll("");

		return WHITESPACE.matcher(noMarks)
				.replaceAll(" ")
				.trim()
				.toLowerCase(Locale.ROOT)
				.replace('đ', 'd');
	}


	// =========================================================
	// HELPER
	// =========================================================

	// en khi lang = en và có bản en, còn lại vi
	private static String pick(String vi, String en, String lang) {
		return "en".equals(lang) && en != null && !en.isBlank() ? en : vi;
	}

	private static String validateScreenKey(String screenKey) {

		if (screenKey == null || screenKey.isBlank()) {
			throw new IllegalArgumentException("screenKey is required.");
		}

		String key = screenKey.trim();

		if (!SCREEN_KEY.matcher(key).matches()) {
			throw new IllegalArgumentException("screenKey is invalid.");
		}

		return key;
	}

	private static String validateLang(String lang) {

		if (lang == null || lang.isBlank()) {
			return "vi";
		}

		String value = lang.trim().toLowerCase(Locale.ROOT);

		if (!"vi".equals(value) && !"en".equals(value)) {
			throw new IllegalArgumentException("lang must be \"vi\" or \"en\".");
		}

		return value;
	}
}
