package com.example.backlogbe.assistant.core.api.dto;

// 1 tool đã gọi thành công khi trả lời. div / cost null nếu tool không có tham số đó.
// month: tháng (yyyy-MM) tool thực sự đã chạy (sau ToolMonthScope, kể cả pre-fetch);
//        null nếu màn hình không có khái niệm tháng.
// Cùng tool + div + cost nhưng khác month là 2 phần tử riêng (record so sánh cả month).
// TODO: khi có màn hình thứ hai: đổi div/cost thành tags chung.
public record AssistantSource(
		String tool,
		String div,
		String cost,
		String month
) {
}
