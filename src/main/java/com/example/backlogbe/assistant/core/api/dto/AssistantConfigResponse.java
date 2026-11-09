package com.example.backlogbe.assistant.core.api.dto;

// GET /api/assistant/config
//   name: tên mascot (assistant.name)
public record AssistantConfigResponse(
		String name
) {
}
