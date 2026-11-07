package com.example.backlogbe.assistant.core.eval;

import java.util.List;

/*
 * Kết quả 1 câu:
 *   missing    : các chuỗi mustContain không có trong câu trả lời
 *   unexpected : các chuỗi mustNotContain lại có trong câu trả lời
 *   error      : lỗi khi gọi trợ lý (vd LLM bận / timeout), null nếu không lỗi
 */
public record EvalResult(
		int index,
		String question,
		String month,
		boolean pass,
		List<String> missing,
		List<String> unexpected,
		String answer,
		List<String> tools,
		String error,
		long durationMs
) {
}
