package com.example.backlogbe.assistant.core.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/*
 * answer       : câu trả lời
 * sources      : các tool đã gọi thành công [{tool, div, cost, month}], không trùng (khác month = khác phần tử),
 *                theo thứ tự gọi (pre-fetch trước)
 * answeredMonth: yyyy-MM khi câu trả lời dùng dữ liệu tháng khác tháng đang xem
 *                (câu hỏi nhắc tháng khác và đã dựng context tháng đó); không có -> bỏ khỏi JSON
 */
public record AssistantChatResponse(
		String answer,
		List<AssistantSource> sources,
		@JsonInclude(JsonInclude.Include.NON_NULL) String answeredMonth
) {

	public AssistantChatResponse(String answer, List<AssistantSource> sources) {
		this(answer, sources, null);
	}
}
