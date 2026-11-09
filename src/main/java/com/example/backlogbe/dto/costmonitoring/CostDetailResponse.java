package com.example.backlogbe.dto.costmonitoring;

import java.time.LocalDate;
import java.util.List;

/*
 * GET /api/cost-monitoring/detail – chi tiết 1 ô (div × chart × tháng) cho drawer.
 *
 * div: PR | MO | Guide        chart: OT | T&SL | RM   (cùng mã với /chart – CostChartDto)
 * asOfDate: ngày có actual mới nhất của ô (T&SL ~ hôm qua, RM ~ hôm kia); null = chưa có actual
 * kpi     : số K$ làm tròn 1 chữ số; chênh lệch tính từ số đã làm tròn
 * daily   : ngày 1 -> asOfDate; Σ daily = kpi.actMtd (cùng số làm tròn)
 * transactions: dòng xuất kho trực tiếp (không gồm phân bổ F2 / HT2 / HT-G), OT luôn rỗng
 */
public record CostDetailResponse(
		String month,
		String div,
		String chart,
		String simuWeek,
		String unit,
		int daysInMonth,
		LocalDate asOfDate,
		boolean monthClosed,
		Kpi kpi,
		List<Daily> daily,
		TransactionPage transactions
) {

	public static final String STATUS_OVER = "OVER";
	public static final String STATUS_OK = "OK";

	/*
	 * fc, fluct           : FC TGT, Fluct cả tháng (giá trị Fluct mới nhất, giống nhãn cuối đường chart)
	 * ratioPct            : Ratio Sale&Inven mới nhất của bộ phận, % (Fluct = FC × (1 + Ratio))
	 * actMtd              : Actual lũy kế đến asOfDate (null = chưa có actual, vd OT)
	 * estimateEom         : Estimate cuối tháng (đường Estimate theo PL; tháng đã qua = Actual)
	 * pctOfFluct          : Actual / Fluct × 100 (1 chữ số, như badge)
	 * currentRate         : tốc độ chi hiện tại = Actual / số ngày đến asOfDate (K$/ngày, 2 chữ số)
	 * projectedEom        : dự báo cuối tháng theo tốc độ chi hiện tại = currentRate × N
	 *                       (tháng đã kết thúc = Actual cả tháng)
	 * projectedOver       : projectedEom − Fluct (dương = dự báo vượt Fluct)
	 * projectedOverFc     : projectedEom − FC
	 * status              : OVER (Actual > Fluct) | OK; null khi chưa có Actual hoặc Fluct
	 */
	public record Kpi(
			Double fc,
			Double fluct,
			Double ratioPct,
			Double actMtd,
			Double estimateEom,
			Double pctOfFluct,
			Double currentRate,
			Double projectedEom,
			Double projectedOver,
			Double projectedOverFc,
			String status
	) {
	}

	/*
	 * 1 ngày:
	 *   daily      = cumulative hôm nay − cumulative hôm trước (âm = trả hàng / điều chỉnh)
	 *   cumulative = Actual lũy kế (K$, 1 chữ số)
	 *   ratioPct   = Ratio của bộ phận ngày đó, % (null = không có trong F2_Working_Date)
	 *   fluct      = Fluct tính tại ngày đó (giống tooltip chart)
	 *   diff       = cumulative − fluct (dương = vượt Fluct, âm = còn dư)
	 */
	public record Daily(
			LocalDate date,
			Double daily,
			Double cumulative,
			Double ratioPct,
			Double fluct,
			Double diff
	) {
	}

	/*
	 * from / to   : khoảng ngày đã lọc (to = asOfDate, cùng mốc với Actual; date filter -> from = to = date)
	 * totalAmountK: tổng thành tiền mọi dòng thỏa bộ lọc (không chỉ trang hiện tại), K$ 2 chữ số
	 * allocationK : actMtd − tổng trực tiếp cả kỳ (phân bổ F2 / HT2 / HT-G + điều chỉnh); null khi lọc theo ngày
	 */
	public record TransactionPage(
			LocalDate from,
			LocalDate to,
			int page,
			int size,
			long totalElements,
			int totalPages,
			Double totalAmountK,
			Double allocationK,
			List<Transaction> items
	) {

		public static TransactionPage empty(int page, int size) {
			return new TransactionPage(null, null, page, size, 0, 0, null, null, List.of());
		}

		public TransactionPage withAllocationK(Double value) {
			return new TransactionPage(from, to, page, size, totalElements, totalPages, totalAmountK, value, items);
		}
	}

	/*
	 * 1 dòng xuất kho (KVH.dbo.CostTool / CostRm):
	 *   code / name = mã vật tư (part) / tên ([Text]); qty, qtyUnit; unitPrice (đơn giá);
	 *   amount = thành tiền ($, như bảng nguồn), amountK = amount / 1000;
	 *   costCenter / costCenterName = PLcostcenter / PLcostcentername; machine.
	 */
	public record Transaction(
			LocalDate date,
			String code,
			String name,
			String category,
			Double qty,
			String qtyUnit,
			Double unitPrice,
			Double amount,
			Double amountK,
			String costCenter,
			String costCenterName,
			String machine
	) {
	}
}
