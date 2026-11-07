package com.example.backlogbe.assistant.core.tool;

import java.util.Map;

// =========================================================
// TOOL CHO LLM (OpenAI tools format)
//
// Màn hình khai báo qua ScreenModule.tools().
// execute:
//   args        : tham số model truyền (đã parse từ JSON)
//   screenParams: tham số màn hình FE gửi (vd month) — dùng cho các giá trị
//                 không cho model tự đặt.
// Kết quả: dạng "Nhãn dễ đọc=giá trị" theo lang, không gửi mã nội bộ cho model.
// Sai tham số -> ném IllegalArgumentException, message dạng key=value
// (service trả "error=invalid_argument|<message>" cho model).
// =========================================================
public interface AssistantTool {

	String name();

	String description();

	// JSON Schema của tham số (object), dạng Map để serialize thẳng vào request
	Map<String, Object> parameters();

	// lang: "vi" | "en" — nhãn trong kết quả theo ngôn ngữ này
	String execute(Map<String, Object> args, Map<String, String> screenParams, String lang);

	// Số ký tự tối đa của kết quả gửi cho model (dài hơn thì service cắt + "(đã rút gọn)")
	default int maxResultLength(Map<String, Object> args) {
		return DEFAULT_MAX_RESULT_LENGTH;
	}

	int DEFAULT_MAX_RESULT_LENGTH = 3000;
}
