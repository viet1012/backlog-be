package com.example.backlogbe.assistant.core.suggestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/*
 * resources/assistant/screens/{screenKey}/suggestions.json
 * {
 *   "screenKey": "...",
 *   "dynamic": [ { "when": "over", "vi": "Vì sao {div} {cost} vượt target?", "en": "..." } ],
 *   "static":  { "overview": [ { "vi": "...", "en": "..." } ], "concept": [ ... ] }
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SuggestionFile(
		String screenKey,
		List<DynamicTemplate> dynamic,
		@JsonProperty("static") Map<String, List<StaticText>> staticGroups
) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DynamicTemplate(
			String when,
			String vi,
			String en
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StaticText(
			String vi,
			String en
	) {
	}
}
