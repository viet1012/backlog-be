package com.example.backlogbe.dto.costmonitoring;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/*
 * GET /api/cost-monitoring/ot/status?month=yyyy-MM
 *
 * Tình trạng dữ liệu OT đã nạp trong tháng, 1 dòng / SubDiv (PR, MO, PG, MG – luôn đủ 4 dòng).
 * lastDate null = tháng này chưa nạp OT cho SubDiv đó.
 * shareImportEnabled: BE có cấu hình đường dẫn file trên share (FE hiện nút "Lấy từ server").
 */
public record CostOtStatusResponse(
		String month,
		String unit,
		boolean shareImportEnabled,
		List<Row> rows
) {

	public record Row(
			String subDiv,
			String div,
			int days,
			LocalDate lastDate,
			Double totalK,
			LocalDateTime loadedAt,
			String source
	) {
	}
}
