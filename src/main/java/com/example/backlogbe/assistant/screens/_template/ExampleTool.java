package com.example.backlogbe.assistant.screens._template;

import com.example.backlogbe.assistant.core.tool.AssistantTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// =========================================================
// MẪU TOOL – SAO CHÉP, KHÔNG DÙNG TRỰC TIẾP (không có @Component)
//
// - name(): snake_case, động từ + đối tượng (vd get_daily_series). Nhắc tên này trong rules.md / knowledge.md.
// - description(): nói rõ KHI NÀO gọi (model chọn tool theo câu này).
// - parameters(): JSON Schema; dùng enum cho giá trị cố định. Màn hình hỗ trợ tháng có thể thêm "month" (yyyy-MM):
//   core (ToolMonthScope) kiểm tra và đổi params màn hình trước khi gọi execute.
// - execute(): tháng / phạm vi lấy từ screenParams (không cho model tự đặt); sai tham số -> IllegalArgumentException
//   với message "arg=<tên>|allowed=<…>" (core trả "error=invalid_argument|…" cho model).
// - Kết quả: "Nhãn=giá trị" phân cách "|", bảng thì dòng "[Tên mục] cột=a|b|c" rồi từng dòng; nhãn theo lang.
// - Tham số "div" / "cost" (nếu có) được ghi vào sources của response.
// =========================================================
public class ExampleTool implements AssistantTool {

	@Override
	public String name() {
		return "get_example";
	}

	@Override
	public String description() {
		return "Mô tả khi nào model nên gọi tool này.";
	}

	@Override
	public Map<String, Object> parameters() {

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("item", Map.of(
				"type", "string",
				"description", "Đối tượng cần tra"
		));

		return Map.of(
				"type", "object",
				"properties", properties,
				"required", List.of("item")
		);
	}

	@Override
	public String execute(Map<String, Object> args, Map<String, String> screenParams, String lang) {

		Object item = args.get("item");

		if (item == null || item.toString().isBlank()) {
			throw new IllegalArgumentException("arg=item|error=required");
		}

		return "Item=" + item + "|Month=" + screenParams.getOrDefault("month", "");
	}
}
