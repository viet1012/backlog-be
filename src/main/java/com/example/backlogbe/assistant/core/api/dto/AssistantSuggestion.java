package com.example.backlogbe.assistant.core.api.dto;

// 1 câu gợi ý. kind: "dynamic" (theo dữ liệu màn hình) | "static"
public record AssistantSuggestion(
		String text,
		String kind
) {

	public static final String DYNAMIC = "dynamic";
	public static final String STATIC = "static";
}
