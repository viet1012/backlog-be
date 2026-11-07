package com.example.backlogbe.assistant.core.screen;

import java.util.regex.Pattern;

/*
 * 1 tham số màn hình hợp lệ.
 *   name       : tên param FE gửi (vd "month")
 *   pattern    : định dạng hợp lệ (regex, khớp toàn bộ giá trị đã trim); null = không kiểm tra
 *   description: mô tả ngắn (tài liệu, log)
 */
public record ParamSpec(
		String name,
		Pattern pattern,
		String description
) {

	public static ParamSpec of(String name, String regex, String description) {
		return new ParamSpec(name, regex != null ? Pattern.compile(regex) : null, description);
	}

	public boolean matches(String value) {
		return pattern == null || value == null || pattern.matcher(value.trim()).matches();
	}
}
