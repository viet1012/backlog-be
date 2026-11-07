package com.example.backlogbe.assistant.core.eval;

import java.util.List;

public record EvalReport(
		String screenKey,
		int total,
		int passed,
		int failed,
		long durationMs,
		List<EvalResult> results
) {
}
