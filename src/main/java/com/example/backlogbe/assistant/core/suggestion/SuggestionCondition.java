package com.example.backlogbe.assistant.core.suggestion;

/*
 * 1 điều kiện đang đúng trên màn hình, dùng để chọn câu gợi ý động.
 *   when   : khớp "when" trong file JSON (vd "over", "forecast_over")
 *   div    : điền {div}; null nếu điều kiện không theo bộ phận (vd "no_pl")
 *   cost   : điền {cost}; null nếu điều kiện không theo loại chi phí
 *   tier   : độ ưu tiên, nhỏ hơn = ưu tiên hơn
 *   weight : trong cùng tier, lớn hơn = ưu tiên hơn (vd phần vượt K$)
 * TODO: khi có màn hình thứ hai: đổi div/cost thành tags chung.
 */
public record SuggestionCondition(
		String when,
		String div,
		String cost,
		int tier,
		double weight
) {

	// Khóa "khung" để không gợi ý 2 câu động cho cùng 1 khung
	public String frameKey() {
		return (div != null ? div : "-") + "|" + (cost != null ? cost : "-");
	}
}
