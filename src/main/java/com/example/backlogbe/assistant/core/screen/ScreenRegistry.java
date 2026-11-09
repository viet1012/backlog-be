package com.example.backlogbe.assistant.core.screen;

import com.example.backlogbe.assistant.core.tool.AssistantTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// =========================================================
// SCREEN REGISTRY
//
// Gom mọi bean ScreenModule theo screenKey (trùng key -> lỗi khi khởi động).
// screenKey null / không có module -> context rỗng, không tool, không prefetch.
// =========================================================
@Slf4j
@Component
public class ScreenRegistry {

	private final Map<String, ScreenModule> modules;

	public ScreenRegistry(ObjectProvider<ScreenModule> modules) {

		Map<String, ScreenModule> map = new LinkedHashMap<>();

		modules.orderedStream().forEach(module -> {
			ScreenModule previous = map.put(module.screenKey(), module);
			if (previous != null) {
				throw new IllegalStateException("Duplicate assistant screenKey: " + module.screenKey());
			}
		});

		this.modules = Collections.unmodifiableMap(map);

		log.info("Assistant screens registered: {}", this.modules.keySet());
	}

	// null nếu không có module
	public ScreenModule find(String screenKey) {
		return screenKey != null ? modules.get(screenKey) : null;
	}

	public Set<String> screenKeys() {
		return modules.keySet();
	}

	// Không có module -> ""; module trả null -> ""
	public String buildContext(String screenKey, Map<String, String> params, String lang) {
		return buildContext(screenKey, params, lang, null);
	}

	// question: câu hỏi gốc (null = context đầy đủ)
	public String buildContext(String screenKey, Map<String, String> params, String lang, String question) {

		ScreenModule module = find(screenKey);

		if (module == null) {
			return "";
		}

		Map<String, String> safeParams = params != null ? params : Map.of();
		String context = question == null
				? module.buildContext(safeParams, lang)
				: module.buildContext(safeParams, lang, question);

		return context != null ? context : "";
	}

	// Không có module -> rỗng
	public List<AssistantTool> tools(String screenKey) {

		ScreenModule module = find(screenKey);

		if (module == null) {
			return List.of();
		}

		List<AssistantTool> tools = module.tools();

		return tools != null ? tools : List.of();
	}

	/*
	 * Đối chiếu params với paramSpec() của màn hình: chỉ log WARN (tên param, không log giá trị),
	 * không chặn request – giữ hành vi cũ (service của màn hình tự validate và trả 400 nếu cần).
	 */
	public void checkParams(String screenKey, Map<String, String> params) {

		ScreenModule module = find(screenKey);

		if (module == null || params == null || params.isEmpty() || module.paramSpec().isEmpty()) {
			return;
		}

		List<String> unknown = new ArrayList<>();
		List<String> invalid = new ArrayList<>();

		for (Map.Entry<String, String> entry : params.entrySet()) {

			ParamSpec spec = module.paramSpec().stream()
					.filter(s -> s.name().equals(entry.getKey()))
					.findFirst()
					.orElse(null);

			if (spec == null) {
				unknown.add(entry.getKey());
			} else if (!spec.matches(entry.getValue())) {
				invalid.add(entry.getKey());
			}
		}

		if (!unknown.isEmpty() || !invalid.isEmpty()) {
			log.warn("Assistant params screenKey={} unknown={} invalidFormat={}", screenKey, unknown, invalid);
		}
	}
}
