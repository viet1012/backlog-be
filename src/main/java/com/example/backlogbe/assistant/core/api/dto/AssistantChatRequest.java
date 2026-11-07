package com.example.backlogbe.assistant.core.api.dto;

import java.util.List;
import java.util.Map;

/*
 * POST /api/assistant/chat
 * screenKey: màn hình đang mở (vd "cost-monitoring"), quyết định knowledge + context.
 * params: tham số màn hình (theo ScreenModule.paramSpec của screenKey).
 * question: câu hỏi.
 * history: các lượt trước (role user|assistant), chỉ giữ 6 lượt cuối.
 * lang: ngôn ngữ trả lời "vi" | "en" (bỏ trống = "vi").
 */
public record AssistantChatRequest(
		String screenKey,
		Map<String, String> params,
		String question,
		List<ChatMessage> history,
		String lang
) {
}
