package com.example.backlogbe.assistant.core.eval;

import java.util.List;

/*
 * 1 câu hỏi kiểm tra trong resources/assistant/screens/{screenKey}/eval.json
 *   question       : câu hỏi
 *   month          : yyyy-MM (tháng đang xem trên màn hình)
 *   mustContain    : câu trả lời phải chứa (không phân biệt hoa thường)
 *   mustNotContain : câu trả lời không được chứa (không phân biệt hoa thường)
 */
public record EvalCase(
		String question,
		String month,
		List<String> mustContain,
		List<String> mustNotContain
) {
}
