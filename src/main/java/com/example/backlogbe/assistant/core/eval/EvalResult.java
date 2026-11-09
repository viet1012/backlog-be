package com.example.backlogbe.assistant.core.eval;

import java.util.List;

/*
 * Kết quả 1 câu:
 *   missing      : các chuỗi mustContain không có trong câu trả lời
 *   unexpected   : các chuỗi mustNotContain lại có trong câu trả lời
 *   error        : lỗi khi gọi trợ lý (vd LLM bận / timeout), null nếu không lỗi
 *   llmCallCount : số lần gọi LLM thực tế (0 = câu trả lời template)
 *   answerSource : "llm" | "template" (null = lỗi trước khi chọn)
 *   answerIntent : loại câu hỏi màn hình phân loại (null = không khớp)
 *   mismatches   : kỳ vọng llmCallCount / answerSource / answerIntent không khớp, vd "answerIntent: expected=ranking
 *                  actual=overview" (có -> pass = false)
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
		long durationMs,
		int llmCallCount,
		String answerSource,
		String answerIntent,
		List<String> mismatches
) {
}
