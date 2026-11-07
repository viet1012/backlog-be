package com.example.backlogbe.assistant.core.api.dto;

import java.util.List;

// GET /api/assistant/suggestions
//   greeting   : câu chào theo dữ liệu màn hình; null nếu màn hình không có hoặc không lấy được dữ liệu
//   suggestions: câu gợi ý
public record AssistantSuggestionsResponse(
		String greeting,
		List<AssistantSuggestion> suggestions
) {
}
