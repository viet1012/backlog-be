package com.example.backlogbe.dto.costmonitoring;

import java.time.LocalDate;
import java.util.List;

/*
 * GET /api/cost-monitoring/detail/transactions/facets – giá trị cho dropdown lọc 1 cột của bảng giao dịch.
 *
 * column   : date | category | costCenter | machine
 * from / to: khoảng ngày đã lọc (như /detail/transactions)
 * total    : tổng số giá trị khác nhau (trước khi cắt theo limit)
 * truncated: true khi total > values.size() (FE nhắc gõ để thu hẹp)
 * values   : value = giá trị gửi lại khi lọc ("" = ô trống); label = nhãn hiển thị; count = số dòng giao dịch
 *            date: value yyyy-MM-dd, tăng dần theo ngày; cột khác: A→Z; "" luôn cuối.
 */
public record CostTransactionFacetsResponse(
		String column,
		LocalDate from,
		LocalDate to,
		long total,
		boolean truncated,
		List<Value> values
) {

	public record Value(
			String value,
			String label,
			long count
	) {
	}
}
