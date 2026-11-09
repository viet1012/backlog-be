package com.example.backlogbe.assistant.core.screen;

import java.util.List;

// =========================================================
// HƯỚNG DẪN TRẢ LỜI do màn hình tính sẵn cho 1 câu hỏi (ScreenModule.answerGuide)
//
// conclusion     : khối kết luận (đã gồm tiêu đề + dòng chỉ thị) đặt ở cuối user message, ngay trước "Câu hỏi:".
//                  null / rỗng = không chèn.
// toolsSufficient: pre-fetch của câu hỏi này đã đủ để trả lời -> nếu pre-fetch thực sự ra kết quả (không lỗi),
//                  ChatOrchestrator gọi LLM với tool_choice "none" (model không gọi thêm tool).
// intent         : nhãn loại câu hỏi (chỉ ghi audit log / log WARN, không gửi model), vd "overview". null = không khớp.
// directAnswer   : câu trả lời hoàn chỉnh do code render (template, markdown). Có giá trị và
//                  assistant.template-answers.enabled = true -> ChatOrchestrator trả luôn, KHÔNG gọi LLM,
//                  không chạy context / pre-fetch. null = trả lời qua LLM như bình thường.
// suggestions    : câu hỏi gợi ý tiếp theo kèm câu trả lời template (response.suggestions); rỗng = không có.
// intentMatch    : chuỗi debug cách phân loại (intent + từ khóa đã khớp), chỉ ghi audit (field intentMatch).
// =========================================================
public record AnswerGuide(
		String conclusion,
		boolean toolsSufficient,
		String intent,
		String directAnswer,
		List<String> suggestions,
		String intentMatch
) {

	public AnswerGuide {
		suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
	}

	public AnswerGuide(String conclusion, boolean toolsSufficient, String intent) {
		this(conclusion, toolsSufficient, intent, null, List.of(), null);
	}

	// Chỉ ghi nhận cách phân loại (không kết luận, không template)
	public static AnswerGuide matchOnly(String intent, String intentMatch) {
		return new AnswerGuide(null, false, intent, null, List.of(), intentMatch);
	}

	public boolean hasConclusion() {
		return conclusion != null && !conclusion.isBlank();
	}

	public boolean hasDirectAnswer() {
		return directAnswer != null && !directAnswer.isBlank();
	}
}
