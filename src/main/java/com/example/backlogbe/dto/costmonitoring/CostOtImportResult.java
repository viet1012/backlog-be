package com.example.backlogbe.dto.costmonitoring;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/*
 * Kết quả POST /api/cost-monitoring/ot/import và /ot/import-from-share.
 *
 * dryRun = true : chỉ đọc file, trả tóm tắt, KHÔNG ghi DB (FE dùng để xem trước).
 * rowsWritten   : số dòng (ngày × SubDiv) đã ghi; dryRun -> 0.
 * summaries     : 1 dòng / (tháng, SubDiv) có số trong file (sau lọc month).
 * warnings      : dòng bị bỏ qua (ô lỗi #REF!, chữ, ngày trùng...) – không chặn import.
 * Đơn vị K$.
 */
public record CostOtImportResult(
		String source,
		boolean dryRun,
		LocalDateTime importedAt,
		int rowsWritten,
		List<Summary> summaries,
		List<String> warnings
) {

	/*
	 * subDiv: PR | MO | PG | MG (div: PR | MO | Guide – Guide = PG + MG)
	 * days  : số ngày có số OT (kể cả 0)
	 * totalK: tổng OT trong tháng (K$, 2 chữ số)
	 */
	public record Summary(
			String month,
			String subDiv,
			String div,
			String sheet,
			int days,
			LocalDate firstDate,
			LocalDate lastDate,
			Double totalK
	) {
	}
}
