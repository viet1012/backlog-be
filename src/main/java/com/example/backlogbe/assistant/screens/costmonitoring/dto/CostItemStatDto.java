package com.example.backlogbe.assistant.screens.costmonitoring.dto;

import java.time.LocalDate;

/**
 * Thống kê 1 vật tư (Code + QtyUnit) trong 1 kỳ – dùng cho câu hỏi chi tiết vật tư của trợ lý AI
 * (xuất nhiều lần nhất, ngày xuất nhiều nhất, đơn giá thay đổi, bất thường).
 *
 * period  : null (cả khoảng ngày) | "yyyy-MM" (theo tháng) | "yyyy-MM-dd" (theo ngày)
 * qty     : tổng số lượng (gồm dòng âm / trả hàng)
 * amountK : tổng thành tiền K$ (gồm dòng âm)
 * lines   : số dòng xuất kho; days: số ngày có xuất
 * posQty / posAmount: tổng số lượng / thành tiền (USD) của các dòng Qty > 0 và Amount > 0 – dùng tính đơn giá bình quân
 * minPrice / maxPrice: đơn giá dòng (Amount / Qty, USD) nhỏ nhất / lớn nhất trong kỳ
 */
public record CostItemStatDto(
		String period,
		String code,
		String name,
		String category,
		String qtyUnit,
		Double qty,
		Double amountK,
		int lines,
		int days,
		Double posQty,
		Double posAmount,
		Double minPrice,
		Double maxPrice,
		LocalDate lastDate
) {

	// Đơn giá bình quân (USD / đơn vị) = Σ thành tiền / Σ số lượng của dòng dương; không có -> null
	public Double avgPrice() {
		return posQty == null || posAmount == null || posQty <= 0 ? null : posAmount / posQty;
	}

	public String key() {
		return code + "|" + (qtyUnit == null ? "" : qtyUnit);
	}
}
