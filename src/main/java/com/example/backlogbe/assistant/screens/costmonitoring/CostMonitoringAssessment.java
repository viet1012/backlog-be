package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.dto.costmonitoring.CostChartDto;

// =========================================================
// KẾT LUẬN CỦA 1 KHUNG (tính bằng code, model chỉ diễn đạt lại)
//
// Pct      = Act / Fluct × 100            Pace = D / N × 100
// PaceStatus:
//   FASTER_THAN_PACE  Pct > Pace + 3
//   SLOWER_THAN_PACE  Pct < Pace − 3
//   ON_PACE           còn lại
// Forecast:
//   có PL  : Est_MTD ngày cuối tháng của sp_CostMonitoringChart
//            (= Act cuối + PL × WD_All còn lại / tổng WD_All tháng; ngày nghỉ nhà máy không cộng PL)
//   không PL: Act + Fluct / N × (N − D)
// ForecastStatus:
//   WILL_EXCEED  Forecast > Fluct (ForecastGap = Forecast − Fluct, phần vượt)
//   WITHIN       Forecast ≤ Fluct (ForecastGap = Fluct − Forecast, phần còn dư)
// Risk:
//   HIGH   đã vượt (Act > Fluct) hoặc WILL_EXCEED
//   WATCH  FASTER_THAN_PACE nhưng WITHIN
//   OK     còn lại
// Kịch bản cuối tháng (luôn theo Fluct, so sánh có dấu: dương = vượt, âm = còn dư):
//   "Nếu từ nay chi đúng target": targetScenario = Act + Fluct / N × (N − D)
//       targetScenarioVsFluct = targetScenario − Fluct, targetScenarioVsFc = targetScenario − FC
//   "Nếu giữ tốc độ hiện tại"   : currentRate = Act / D; linearForecast = currentRate × N
//       linearVsFluct = linearForecast − Fluct, linearVsFc = linearForecast − FC
//   "Ngân sách còn lại mỗi ngày": remainingPerDay = (Fluct − Act) / (N − D)  (âm = đã hết ngân sách)
//   "Nhịp target mỗi ngày"      : fluctDailyRate = Fluct / N
// targetRate = Fluct / N khi Forecast theo Fluct; null khi theo PL.
// Mode (chế độ phân tích của explain_variance):
//   IN_PROGRESS  Act ≤ Fluct: dùng ExcessVsPace = Act − Fluct × D / N
//                (= phần dự báo vượt khi Forecast tính theo Fluct)
//   OVER         Act > Fluct: tách Gap thành TargetCut / OverFC
// Thiếu Act / Fluct -> các status tương ứng là NO_DATA.
// Mọi phần chênh hiển thị (forecastGap, excessVsPace, so sánh kịch bản với Fluct / FC, monthResultGap,
//   Fluct − Act trong ngân sách còn lại) và so sánh dự báo với Fluct tính từ số đã làm tròn 1 chữ số
//   (CmText.r1 / subR1) để khớp phép trừ người dùng tự làm trên màn hình.
//   pct / badge / mode (Act > Fluct) giữ giá trị chưa làm tròn để khớp badge FE.
// Tháng đã kết thúc (closed: tháng đang xem < tháng hiện tại, hoặc D = N):
//   không tính tiến độ, dự báo, kịch bản, ngân sách còn lại (NO_DATA / null);
//   risk = MONTH_CLOSED, thay bằng Kết quả tháng: monthResultGap = Act − Fluct
//   ("Vượt Fluct +x K$" / "Trong Fluct, còn dư x K$");
//   mode = OVER (Act > Fluct) hoặc CLOSED_WITHIN.
// =========================================================
public record CostMonitoringAssessment(
		Double act,
		Double fluct,
		Double pct,
		Double pace,
		String paceStatus,
		Double forecast,
		String forecastBase,
		String forecastStatus,
		Double forecastGap,
		String risk,
		String mode,
		Double excessVsPace,
		Double currentRate,
		Double targetRate,
		Double linearForecast,
		Double remainingPerDay,
		Double fc,
		Double targetScenario,
		Double targetScenarioVsFluct,
		Double targetScenarioVsFc,
		Double linearVsFluct,
		Double linearVsFc,
		Double fluctDailyRate,
		boolean closed,
		Double monthResultGap
) {

	public static final String MODE_IN_PROGRESS = "IN_PROGRESS";
	public static final String MODE_OVER = "OVER";
	public static final String MODE_CLOSED_WITHIN = "CLOSED_WITHIN";

	// Rủi ro khi tháng đã kết thúc: thay bằng "Kết quả tháng"
	public static final String MONTH_CLOSED = "MONTH_CLOSED";

	public static final String FASTER_THAN_PACE = "FASTER_THAN_PACE";
	public static final String ON_PACE = "ON_PACE";
	public static final String SLOWER_THAN_PACE = "SLOWER_THAN_PACE";

	public static final String WILL_EXCEED = "WILL_EXCEED";
	public static final String WITHIN = "WITHIN";

	public static final String RISK_HIGH = "HIGH";
	public static final String RISK_WATCH = "WATCH";
	public static final String RISK_OK = "OK";

	public static final String NO_DATA = "NO_DATA";

	// Ngưỡng lệch tiến độ (điểm %)
	private static final double PACE_TOLERANCE = 3.0;

	public static CostMonitoringAssessment of(CostChartDto chart, int d, int n) {
		return of(chart, d, n, false);
	}

	public static CostMonitoringAssessment of(CostMonitoringSnapshot snapshot, CostChartDto chart) {
		return of(chart, snapshot.d(), snapshot.n(), snapshot.monthClosed());
	}

	// closed = tháng đã kết thúc: chỉ có Kết quả tháng (Act − Fluct), không tiến độ / dự báo / kịch bản / ngân sách
	public static CostMonitoringAssessment of(CostChartDto chart, int d, int n, boolean closed) {

		Double act = chart.actMtdLatest();
		Double fluct = CostMonitoringSnapshot.fluct(chart);
		Double pl = chart.plTgt();
		Double fc = chart.fcTgt();

		Double pct = act != null && fluct != null && fluct != 0 ? act / fluct * 100 : null;

		if (closed) {
			return closedMonth(act, fluct, fc, pct);
		}
		Double pace = n > 0 ? (double) d / n * 100 : null;

		String paceStatus = paceStatus(pct, pace);

		// Forecast theo PL = Est_MTD ngày cuối tháng của SP (PL phân bổ theo WD_All, không tự tính PL / N).
		// Không có PL / Est -> theo Fluct: Act + Fluct / N × (N − D)
		int remaining = Math.max(0, n - d);
		Double estMonthEnd = chart.estMonthEnd();
		boolean byPl = pl != null && estMonthEnd != null;
		String forecastBase = byPl ? "PL" : fluct != null ? "Fluct" : null;
		Double forecast;
		if (act == null) {
			forecast = null;
		} else if (byPl) {
			forecast = estMonthEnd;
		} else {
			forecast = fluct != null && n > 0 ? act + fluct / n * remaining : null;
		}

		// So sánh và phần chênh tính từ số đã làm tròn 1 chữ số (giống số hiển thị)
		String forecastStatus;
		Double forecastGap;
		if (forecast == null || fluct == null) {
			forecastStatus = NO_DATA;
			forecastGap = null;
		} else if (CmText.r1(forecast) > CmText.r1(fluct)) {
			forecastStatus = WILL_EXCEED;
			forecastGap = CmText.subR1(forecast, fluct);
		} else {
			forecastStatus = WITHIN;
			forecastGap = CmText.subR1(fluct, forecast);
		}

		String risk;
		if (act == null || fluct == null) {
			risk = NO_DATA;
		} else if (act > fluct || WILL_EXCEED.equals(forecastStatus)) {
			risk = RISK_HIGH;
		} else if (FASTER_THAN_PACE.equals(paceStatus) && WITHIN.equals(forecastStatus)) {
			risk = RISK_WATCH;
		} else {
			risk = RISK_OK;
		}

		String mode;
		Double excessVsPace = null;
		if (act == null || fluct == null) {
			mode = NO_DATA;
		} else if (act > fluct) {
			mode = MODE_OVER;
		} else {
			mode = MODE_IN_PROGRESS;
			// Act so với phần Fluct "đáng lẽ đã dùng" đến ngày D; > 0 = chi nhiều hơn tiến độ
			// (r1(Act) − r1(Theo tiến độ nên chi), giống 2 số hiển thị)
			excessVsPace = n > 0 ? CmText.subR1(act, fluct * d / n) : null;
		}

		Double currentRate = act != null && d > 0 ? act / d : null;
		// Nhịp theo ngày lịch chỉ có nghĩa khi dự báo theo Fluct (PL phân bổ theo ngày làm việc)
		Double targetRate = !byPl && fluct != null && n > 0 ? fluct / n : null;

		// "Nếu giữ tốc độ hiện tại" = Act / D × N
		Double linearForecast = currentRate != null && n > 0 ? currentRate * n : null;

		// "Nếu từ nay chi đúng target" = Act + Fluct / N × (N − D)
		Double fluctDailyRate = fluct != null && n > 0 ? fluct / n : null;
		Double targetScenario = act != null && fluctDailyRate != null
				? act + fluctDailyRate * remaining
				: null;

		// "Ngân sách còn lại mỗi ngày" = (Fluct − Act) / (N − D), Fluct − Act từ số đã làm tròn; hết ngày thì không tính
		Double remainingPerDay = act != null && fluct != null && remaining > 0
				? CmText.subR1(fluct, act) / remaining
				: null;

		return new CostMonitoringAssessment(
				act, fluct, pct, pace, paceStatus,
				forecast, forecastBase, forecastStatus, forecastGap, risk,
				mode, excessVsPace,
				currentRate, targetRate, linearForecast,
				remainingPerDay,
				fc,
				targetScenario, CmText.subR1(targetScenario, fluct), CmText.subR1(targetScenario, fc),
				CmText.subR1(linearForecast, fluct), CmText.subR1(linearForecast, fc),
				fluctDailyRate,
				false, CmText.subR1(act, fluct)
		);
	}

	// Tháng đã kết thúc: mode OVER (Act > Fluct) hoặc CLOSED_WITHIN; risk = MONTH_CLOSED;
	// tiến độ / dự báo = NO_DATA, kịch bản / ngân sách = null
	private static CostMonitoringAssessment closedMonth(Double act, Double fluct, Double fc, Double pct) {

		String mode = act == null || fluct == null ? NO_DATA
				: act > fluct ? MODE_OVER
				: MODE_CLOSED_WITHIN;

		return new CostMonitoringAssessment(
				act, fluct, pct, null, NO_DATA,
				null, null, NO_DATA, null,
				act == null || fluct == null ? NO_DATA : MONTH_CLOSED,
				mode, null,
				null, null, null,
				null,
				fc,
				null, null, null,
				null, null,
				null,
				true, CmText.subR1(act, fluct)
		);
	}

	// Pct so với Pace (± 3 điểm %); thiếu số -> NO_DATA
	public static String paceStatus(Double pct, Double pace) {

		if (pct == null || pace == null) {
			return NO_DATA;
		}

		if (pct > pace + PACE_TOLERANCE) {
			return FASTER_THAN_PACE;
		}

		if (pct < pace - PACE_TOLERANCE) {
			return SLOWER_THAN_PACE;
		}

		return ON_PACE;
	}

	// Nhãn hiển thị cho model: xem CmText (không gửi các mã ở đây ra ngoài)
}
