package com.example.backlogbe.assistant.core.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/*
 * Thẻ tóm tắt kèm câu trả lời (số do code tính, không do LLM). FE vẽ thẻ ở đầu câu trả lời và gập phần chữ chi tiết
 * sau nút "Xem giải thích".
 *
 * tone    : "over" (vượt / dự báo vượt) | "watch" (chi nhanh, còn trong Fluct) | "ok"
 * title   : tên đối tượng, vd "PRESS RM"
 * headline: 1 câu kết luận ngắn (markdown in đậm được)
 * chips   : các số chính [{label, value, tone}]
 * budget  : thanh ngân sách tháng (Actual + phần dự báo còn lại, mốc Fluct / FC); null = không vẽ
 * bridge  : cầu nối từ mốc tới dự báo, vd Fluct → (+target bị hạ) → FC → (+vượt FC) → Dự báo; rỗng = không vẽ
 * unit    : đơn vị số trong budget / bridge ("K$")
 */
public record AssistantCard(
		String tone,
		String title,
		String headline,
		List<Chip> chips,
		@JsonInclude(JsonInclude.Include.NON_NULL) Budget budget,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<Step> bridge,
		String unit
) {

	public static final String OVER = "over";
	public static final String WATCH = "watch";
	public static final String OK = "ok";

	public AssistantCard {
		chips = chips == null ? List.of() : List.copyOf(chips);
		bridge = bridge == null ? List.of() : List.copyOf(bridge);
	}

	// tone: over | ok | neutral
	public record Chip(String label, String value, String tone) {
	}

	/*
	 * actual      : Actual MTD (đến ngày actualDay)
	 * forecast    : dự báo cuối tháng (null = không có -> chỉ vẽ Actual)
	 * fluct / fc  : mốc (null = không có)
	 * *Label      : nhãn hiển thị theo lang
	 */
	public record Budget(
			double actual,
			@JsonInclude(JsonInclude.Include.NON_NULL) Double forecast,
			@JsonInclude(JsonInclude.Include.NON_NULL) Double fluct,
			@JsonInclude(JsonInclude.Include.NON_NULL) Double fc,
			String actualLabel,
			String restLabel,
			String forecastLabel
	) {
	}

	// kind: "base" (mốc, thanh từ 0) | "delta" (bước cộng / trừ) | "total" (kết quả)
	public record Step(String label, double value, String kind) {

		public static final String BASE = "base";
		public static final String DELTA = "delta";
		public static final String TOTAL = "total";
	}
}
