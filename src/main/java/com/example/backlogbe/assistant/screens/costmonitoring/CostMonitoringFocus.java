package com.example.backlogbe.assistant.screens.costmonitoring;

import java.util.Locale;
import java.util.Map;

// =========================================================
// Ô (khung) đang mở khi FE mở chat từ drawer của 1 ô (factory × category × tháng).
//
// screenParams (FE gửi, tùy chọn):
//   factory : PRESS | MOLD | GUIDE (chấp nhận cả mã chart PR / MO / Guide / GU, không phân biệt hoa thường)
//   category: OT | T&SL | RM (chấp nhận TSL)
//   month   : tham số tháng sẵn có của màn hình
// Thiếu / sai factory hoặc category -> không có focus (null), chat chạy như cũ.
// Dùng cho: dòng "Ô đang xem" trong context, giá trị mặc định div / cost của tool (CostMonitoringToolArgs).
// =========================================================
public record CostMonitoringFocus(
		String div,
		String cost
) {

	public static final String PARAM_FACTORY = "factory";
	public static final String PARAM_CATEGORY = "category";

	// Ô đang mở; null nếu FE không gửi đủ factory + category hợp lệ
	public static CostMonitoringFocus from(Map<String, String> params) {

		if (params == null) {
			return null;
		}

		String div = normalizeDiv(params.get(PARAM_FACTORY));
		String cost = normalizeCost(params.get(PARAM_CATEGORY));

		return div != null && cost != null ? new CostMonitoringFocus(div, cost) : null;
	}

	// PRESS / PR -> PRESS, MOLD / MO -> MOLD, GUIDE / GU -> GUIDE; khác -> null
	public static String normalizeDiv(String value) {

		if (value == null || value.isBlank()) {
			return null;
		}

		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "PRESS", "PR" -> "PRESS";
			case "MOLD", "MO" -> "MOLD";
			case "GUIDE", "GU" -> "GUIDE";
			default -> null;
		};
	}

	// OT / T&SL / TSL / RM -> OT / T&SL / RM; khác -> null
	public static String normalizeCost(String value) {

		if (value == null || value.isBlank()) {
			return null;
		}

		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "OT" -> "OT";
			case "T&SL", "TSL" -> "T&SL";
			case "RM" -> "RM";
			default -> null;
		};
	}
}
