package com.example.backlogbe.assistant.core.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/*
 * answer       : câu trả lời
 * sources      : các tool đã gọi thành công [{tool, div, cost, month}], không trùng (khác month = khác phần tử),
 *                theo thứ tự gọi (pre-fetch trước)
 * answeredMonth: yyyy-MM khi câu trả lời dùng dữ liệu tháng khác tháng đang xem
 *                (câu hỏi nhắc tháng khác và đã dựng context tháng đó); không có -> bỏ khỏi JSON
 * suggestions  : câu hỏi gợi ý tiếp theo [{text, kind}] (hiện chỉ có ở câu trả lời template, AnswerGuide.suggestions);
 *                rỗng -> bỏ khỏi JSON
 * charts       : biểu đồ kèm câu trả lời (ScreenModule.answerCharts – số do code tính, không do LLM); rỗng -> bỏ khỏi JSON
 * card         : thẻ tóm tắt đầu câu trả lời (ScreenModule.answerCard); null -> bỏ khỏi JSON
 */
public record AssistantChatResponse(
		String answer,
		List<AssistantSource> sources,
		@JsonInclude(JsonInclude.Include.NON_NULL) String answeredMonth,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<AssistantSuggestion> suggestions,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<AssistantChart> charts,
		@JsonInclude(JsonInclude.Include.NON_NULL) AssistantCard card
) {

	public AssistantChatResponse {
		charts = charts == null ? List.of() : List.copyOf(charts);
	}

	public AssistantChatResponse(String answer, List<AssistantSource> sources, String answeredMonth,
								 List<AssistantSuggestion> suggestions, List<AssistantChart> charts) {
		this(answer, sources, answeredMonth, suggestions, charts, null);
	}

	public AssistantChatResponse(String answer, List<AssistantSource> sources, String answeredMonth,
								 List<AssistantSuggestion> suggestions) {
		this(answer, sources, answeredMonth, suggestions, List.of(), null);
	}

	public AssistantChatResponse(String answer, List<AssistantSource> sources) {
		this(answer, sources, null, List.of(), List.of(), null);
	}

	public AssistantChatResponse(String answer, List<AssistantSource> sources, String answeredMonth) {
		this(answer, sources, answeredMonth, List.of(), List.of(), null);
	}
}
