package com.example.backlogbe.assistant.core.llm;

import com.example.backlogbe.assistant.core.api.dto.ChatMessage;
import com.example.backlogbe.assistant.core.tool.AssistantTool;

import java.time.Duration;
import java.util.List;

public interface LlmClient {

	/**
	 * 1 lần gọi chat completion.
	 *
	 * @param tools   rỗng = không gửi "tools"
	 * @param timeout timeout tối đa của lần gọi này (lấy min với assistant.llm.timeout-seconds)
	 * @return content (đã bỏ reasoning, có thể rỗng) + tool_calls + finish_reason
	 * @throws LlmUnavailableException LLM lỗi HTTP / timeout / không kết nối / không có body
	 */
	default LlmResponse chat(List<ChatMessage> messages, List<AssistantTool> tools, Duration timeout) {
		return chat(messages, tools, timeout, TOOL_CHOICE_AUTO);
	}

	/**
	 * Như trên, chỉ định "tool_choice" (chỉ gửi khi tools không rỗng).
	 * TOOL_CHOICE_NONE: vẫn gửi tools (giữ prefix prompt cho KV cache) nhưng yêu cầu model không gọi tool.
	 */
	LlmResponse chat(List<ChatMessage> messages, List<AssistantTool> tools, Duration timeout, String toolChoice);

	String TOOL_CHOICE_AUTO = "auto";
	String TOOL_CHOICE_NONE = "none";
}
