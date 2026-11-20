package com.example.backlogbe.assistant.core.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/*
 * Biểu đồ kèm câu trả lời (số do code tính từ dữ liệu màn hình, KHÔNG do LLM sinh). FE vẽ ngay dưới câu trả lời.
 *
 * type      : "line" (đường lũy kế theo ngày) | "bar" (cột dọc) | "hbar" (cột ngang – nhiều khung / nhiều mục)
 * title     : tiêu đề ngắn (theo lang)
 * unit      : đơn vị trục giá trị ("K$", "K$/ngày", "%")
 * categories: nhãn trục X (line / bar) hoặc trục Y (hbar)
 * series    : các chuỗi số, mỗi chuỗi cùng độ dài categories; null = không có điểm (đường đứt)
 * refValue  : đường mốc ngang / dọc (vd 100 cho "% Fluct"); null = không có
 * refLabel  : nhãn đường mốc
 * note      : chú thích nhỏ dưới chart; null = không có
 */
public record AssistantChart(
		String type,
		String title,
		String unit,
		List<String> categories,
		List<Series> series,
		@JsonInclude(JsonInclude.Include.NON_NULL) Double refValue,
		@JsonInclude(JsonInclude.Include.NON_NULL) String refLabel,
		@JsonInclude(JsonInclude.Include.NON_NULL) String note
) {

	public static final String LINE = "line";
	public static final String BAR = "bar";
	public static final String HBAR = "hbar";

	public AssistantChart {
		categories = categories == null ? List.of() : List.copyOf(categories);
		series = series == null ? List.of() : List.copyOf(series);
	}

	/*
	 * name : tên chuỗi (legend)
	 * kind : vai trò để FE chọn màu / nét – "actual" | "estimate" | "target" | "fc" | "limit" | "neutral"
	 *        target: Fluct (đỏ), fc: FC (xám, nét đứt), estimate: dự báo (xanh, nét đứt), limit: mức tối đa (xanh lá)
	 * data : giá trị theo categories (null = không có)
	 * status: trạng thái từng điểm để tô màu (chỉ bar / hbar): "over" | "ok" | "stale" | null; cả list null = tô theo kind
	 */
	public record Series(
			String name,
			String kind,
			List<Double> data,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<String> status
	) {

		public static final String ACTUAL = "actual";
		public static final String ESTIMATE = "estimate";
		public static final String TARGET = "target";
		public static final String FC = "fc";
		public static final String LIMIT = "limit";
		public static final String NEUTRAL = "neutral";

		public static final String OVER = "over";
		public static final String OK = "ok";
		// dữ liệu cũ / kém tin cậy (FE tô xám)
		public static final String STALE = "stale";

		public Series(String name, String kind, List<Double> data) {
			this(name, kind, data, null);
		}

		public Series {
			// List.copyOf không nhận null -> giữ null từng điểm
			data = data == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(data));
			status = status == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(status));
		}
	}
}
