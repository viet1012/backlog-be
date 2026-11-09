package com.example.backlogbe.assistant.screens.costmonitoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Đọc / validate tham số tool Cost Monitoring.
// Sai -> IllegalArgumentException với message dạng key=value (vd "arg=div|allowed=PRESS,MOLD,GUIDE"),
// service trả cho model "error=invalid_argument|<message>".
final class CostMonitoringToolArgs {

	private CostMonitoringToolArgs() {
	}

	// PRESS | MOLD | GUIDE (không phân biệt hoa thường)
	static String div(Map<String, Object> args) {

		String value = string(args, "div").toUpperCase(Locale.ROOT);

		if (!CostMonitoringSnapshot.DIVS.contains(value)) {
			throw new IllegalArgumentException("arg=div|allowed=" + String.join(",", CostMonitoringSnapshot.DIVS));
		}

		return value;
	}

	// PRESS | MOLD | GUIDE | ALL (không phân biệt hoa thường)
	static String divOrAll(Map<String, Object> args) {

		String value = string(args, "div").toUpperCase(Locale.ROOT);

		if (!DIVS_WITH_ALL.contains(value)) {
			throw new IllegalArgumentException("arg=div|allowed=" + String.join(",", DIVS_WITH_ALL));
		}

		return value;
	}

	// Model bỏ trống div / cost -> lấy theo ô đang mở từ drawer (CostMonitoringFocus), nếu có
	static String div(Map<String, Object> args, Map<String, String> screenParams) {
		return div(withFocus(args, screenParams));
	}

	static String divOrAll(Map<String, Object> args, Map<String, String> screenParams) {
		return divOrAll(withFocus(args, screenParams));
	}

	static String cost(Map<String, Object> args, List<String> allowed, Map<String, String> screenParams) {
		return cost(withFocus(args, screenParams), allowed);
	}

	private static Map<String, Object> withFocus(Map<String, Object> args, Map<String, String> screenParams) {

		CostMonitoringFocus focus = CostMonitoringFocus.from(screenParams);

		if (focus == null) {
			return args;
		}

		Map<String, Object> result = new LinkedHashMap<>(args);

		if (isBlank(result.get("div"))) {
			result.put("div", focus.div());
		}
		if (isBlank(result.get("cost"))) {
			result.put("cost", focus.cost());
		}

		return result;
	}

	private static boolean isBlank(Object value) {
		return value == null || value.toString().isBlank();
	}

	static final String ALL = "ALL";

	static final List<String> DIVS_WITH_ALL = List.of("PRESS", "MOLD", "GUIDE", ALL);

	// OT | T&SL | RM trong allowed (không phân biệt hoa thường)
	static String cost(Map<String, Object> args, List<String> allowed) {

		String value = string(args, "cost").toUpperCase(Locale.ROOT);

		if (!allowed.contains(value)) {
			throw new IllegalArgumentException("arg=cost|allowed=" + String.join(",", allowed));
		}

		return value;
	}

	static int intValue(Map<String, Object> args, String key, int defaultValue, int min, int max) {

		Object value = args.get(key);

		if (value == null) {
			return defaultValue;
		}

		int result;

		try {
			result = value instanceof Number number
					? number.intValue()
					: Integer.parseInt(value.toString().trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("arg=" + key + "|expected=integer");
		}

		if (result < min || result > max) {
			throw new IllegalArgumentException("arg=" + key + "|min=" + min + "|max=" + max);
		}

		return result;
	}

	private static String string(Map<String, Object> args, String key) {

		Object value = args.get(key);

		if (value == null || value.toString().isBlank()) {
			throw new IllegalArgumentException("arg=" + key + "|error=required");
		}

		return value.toString().trim();
	}


	// =========================================================
	// JSON SCHEMA
	// =========================================================

	// Tham số tùy chọn month: ChatOrchestrator (ToolMonthScope) kiểm tra và đổi tháng trong screenParams
	// trước khi gọi tool; tool không đọc trực tiếp tham số này.
	static final Map<String, Object> MONTH_PROPERTY = Map.of(
			"type", "string",
			"pattern", "^\\d{4}-\\d{2}$",
			"description", "Tháng yyyy-MM (tùy chọn). Bỏ trống = tháng đang xem. Chỉ dùng tháng đang xem "
					+ "hoặc tháng có mục \"[Dữ liệu tháng YYYY-MM (được hỏi trong câu hỏi)]\" trong dữ liệu."
	);

	static Map<String, Object> divCostSchema(List<String> costs, Map<String, Object> extraProperties) {
		return divCostSchema(CostMonitoringSnapshot.DIVS, "Bộ phận", costs, extraProperties);
	}

	static Map<String, Object> divCostSchema(
			List<String> divs,
			String divDescription,
			List<String> costs,
			Map<String, Object> extraProperties
	) {

		Map<String, Object> properties = new LinkedHashMap<>();

		properties.put("div", Map.of(
				"type", "string",
				"enum", divs,
				"description", divDescription
		));
		properties.put("cost", Map.of(
				"type", "string",
				"enum", costs,
				"description", "Loại chi phí"
		));
		properties.putAll(extraProperties);
		properties.put("month", MONTH_PROPERTY);

		return Map.of(
				"type", "object",
				"properties", properties,
				"required", List.of("div", "cost")
		);
	}
}
