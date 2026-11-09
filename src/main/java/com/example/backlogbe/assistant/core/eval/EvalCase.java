package com.example.backlogbe.assistant.core.eval;

import java.util.List;
import java.util.Map;

/*
 * 1 câu hỏi kiểm tra trong resources/assistant/screens/{screenKey}/eval.json
 *   question       : câu hỏi
 *   month          : yyyy-MM (tháng đang xem trên màn hình)
 *   params         : (tùy chọn) tham số màn hình khác gửi kèm như FE, vd ô drawer; month ở trên ghi đè "month" ở đây
 *   mustContain    : câu trả lời phải chứa (không phân biệt hoa thường)
 *   mustNotContain : câu trả lời không được chứa (không phân biệt hoa thường)
 *   llmCallCount   : (tùy chọn) số lần gọi LLM kỳ vọng, vd 0 = câu trả lời template; null = không kiểm
 *   answerSource   : (tùy chọn) "llm" | "template"; null = không kiểm
 *   answerIntent   : (tùy chọn) loại câu hỏi màn hình phân loại (audit answerIntent), vd "ranking"; null = không kiểm
 */
public record EvalCase(
		String question,
		String month,
		Map<String, String> params,
		List<String> mustContain,
		List<String> mustNotContain,
		Integer llmCallCount,
		String answerSource,
		String answerIntent
) {
}
