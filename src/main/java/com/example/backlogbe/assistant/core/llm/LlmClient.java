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
	LlmResponse chat(List<ChatMessage> messages, List<AssistantTool> tools, Duration timeout);
}
