package com.example.backlogbe.dto.costmonitoring;

/*
 * Dự báo / trạng thái của 1 khung do BE tính (CostMonitoringAssessment) – FE hiển thị, không tự tính.
 * Đơn vị K$; mọi phần chênh / % tính từ số đã làm tròn 1 chữ số; % có 1 chữ số thập phân khi FE làm tròn.
 *
 * status (CHÍNH, banner "N khung dự báo vượt" / greeting /suggestions đếm theo field này):
 *   OVER         Actual > Fluct (đã vượt thực tế)
 *   WILL_EXCEED  chưa vượt, estimateEom > Fluct (dự báo vượt)
 *   WITHIN       chưa vượt, estimateEom ≤ Fluct
 *   NO_DATA      thiếu Actual / Fluct
 *   tháng đã kết thúc: OVER | WITHIN theo Actual (không có dự báo)
 * actualPctOfFluct : Actual / Fluct × 100 (badge, chưa làm tròn – khớp badge hiện tại)
 *
 * Dự báo CHÍNH  estimate*  : PL Estimate (estMonthEnd); không có PL -> Actual + Fluct / N × (N − D)
 *   estimateBase "PL" | "Fluct"
 * Dự báo PHỤ   projected* : theo tốc độ chi hiện tại (Actual / ngày có actual × N) – chỉ để tham khảo,
 *   hiển thị phải ghi "theo tốc độ chi hiện tại"
 * *Over = dự báo − Fluct (dương = vượt); *OverFc = dự báo − FC; *PctOfFluct = dự báo / Fluct × 100;
 * *OverPct = *PctOfFluct (làm tròn 1 chữ số) − 100.
 * Tháng đã kết thúc: các field dự báo = null.
 */
public record CostChartForecastDto(
		String status,
		Double actualPctOfFluct,
		String estimateBase,
		Double estimateEom,
		Double estimateOver,
		Double estimateOverFc,
		Double estimatePctOfFluct,
		Double estimateOverPct,
		Double projectedEom,
		Double projectedOver,
		Double projectedOverFc,
		Double projectedPctOfFluct,
		Double projectedOverPct
) {

	public static final String STATUS_OVER = "OVER";
	public static final String STATUS_WILL_EXCEED = "WILL_EXCEED";
	public static final String STATUS_WITHIN = "WITHIN";
	public static final String STATUS_NO_DATA = "NO_DATA";
}
