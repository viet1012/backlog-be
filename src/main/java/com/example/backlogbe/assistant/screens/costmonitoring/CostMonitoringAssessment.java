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
//   "Nếu giữ tốc độ hiện tại"   : currentRate = Act / Dk; linearForecast = currentRate × N
//       Dk = ngày có actual mới nhất của CHÍNH khung (RM thường trễ hơn T&SL 1 ngày); chưa có -> D
//       linearVsFluct = linearForecast − Fluct, linearVsFc = linearForecast − FC
//       projectedEom() = linearForecast, projectedOver() = linearVsFluct, projectedOverFc() = linearVsFc
//       (dự báo cuối tháng theo tốc độ chi hiện tại; dùng cho câu hỏi "cứ chi như vậy / cuối tháng")
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
		return of(chart, d, n, false, d);
	}

	public static CostMonitoringAssessment of(CostMonitoringSnapshot snapshot, CostChartDto chart) {

		int actDay = snapshot.lastActualDay(chart);

		return of(chart, snapshot.d(), snapshot.n(), snapshot.monthClosed(), actDay > 0 ? actDay : snapshot.d());
	}

	public static CostMonitoringAssessment of(CostChartDto chart, int d, int n, boolean closed) {
		return of(chart, d, n, closed, d);
	}

	// closed = tháng đã kết thúc: chỉ có Kết quả tháng (Act − Fluct), không tiến độ / dự báo / kịch bản / ngân sách
	// actDay = ngày có actual mới nhất của khung (mẫu số tốc độ chi hiện tại)
	public static CostMonitoringAssessment of(CostChartDto chart, int d, int n, boolean closed, int actDay) {

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
		// PL trống (null hoặc ≤ 0 – Est khi đó = Actual, sẽ luôn "không vượt") hoặc không có Est
		//   -> theo Fluct: Act + Fluct / N × (N − D)
		int remaining = Math.max(0, n - d);
		Double estMonthEnd = chart.estMonthEnd();
		boolean byPl = pl != null && pl > 0 && estMonthEnd != null;
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

		Double currentRate = act != null && actDay > 0 ? act / actDay : null;
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

	// =========================================================
	// DỰ BÁO CUỐI THÁNG – 2 CÁCH (thống nhất trợ lý / /chart / /suggestions)
	//
	// CHÍNH  estimate*  = PL Estimate (estMonthEnd của SP); không có PL thì Act + Fluct / N × (N − D)
	//                     (= forecast, cùng cách /suggestions đếm "N khung dự báo vượt"); status chính = forecastStatus
	// PHỤ    projected* = tốc độ chi hiện tại (Act / ngày có actual × N); chỉ dùng cho câu "cứ chi như vậy…",
	//                     luôn ghi "theo tốc độ chi hiện tại"
	// Mọi phần chênh / % tính từ số đã làm tròn 1 chữ số. Tháng đã kết thúc: null.
	// =========================================================

	// Dự báo cuối tháng theo định nghĩa chính
	public Double estimateEom() {
		return forecast;
	}

	// "PL" | "Fluct" (không có PL) | null
	public String estimateBase() {
		return forecastBase;
	}

	// estimateEom − Fluct, có dấu (dương = dự báo vượt Fluct)
	public Double estimateOver() {
		return CmText.subR1(forecast, fluct);
	}

	// estimateEom − FC, có dấu
	public Double estimateOverFc() {
		return CmText.subR1(forecast, fc);
	}

	// estimateEom / Fluct × 100 (số đã làm tròn 1 chữ số); thiếu / Fluct ≤ 0 -> null
	public Double estimatePctOfFluct() {
		return pctOf(forecast, fluct);
	}

	// % Fluct (làm tròn 1 chữ số) − 100
	public Double estimateOverPct() {
		return overPct(estimatePctOfFluct());
	}

	// "Vượt FC %" của dự báo chính = (estimateEom / FC × 100, làm tròn 1 chữ số) − 100; thiếu / FC ≤ 0 -> null
	public Double estimateOverFcPct() {
		return overPct(pctOf(forecast, fc));
	}

	// "Vượt FC %" của Actual (tháng đã kết thúc) = (Actual / FC × 100, làm tròn 1 chữ số) − 100
	public Double actOverFcPct() {
		return overPct(pctOf(act, fc));
	}

	// Dự báo chính vượt Fluct (= forecastStatus WILL_EXCEED)
	public boolean estimateWillExceed() {
		return WILL_EXCEED.equals(forecastStatus);
	}

	// Trạng thái chính – HÀM DUY NHẤT cho /chart (forecast.status), /suggestions (over / forecast_over),
	// dòng tổng hợp context và khối KẾT LUẬN của trợ lý:
	//   NO_DATA     thiếu Actual / Fluct
	//   OVER        Actual > Fluct (đã vượt thực tế)
	//   WITHIN      tháng đã kết thúc và không vượt
	//   WILL_EXCEED dự báo chính (PL Estimate; PL trống -> Act + Fluct / N × (N − D)) > Fluct (số làm tròn 1 chữ số)
	//   WITHIN      còn lại
	// PL trống KHÔNG làm dự báo null: forecast luôn có fallback theo Fluct khi có Actual và Fluct.
	public String mainStatus() {

		if (act == null || fluct == null) {
			return NO_DATA;
		}
		if (MODE_OVER.equals(mode)) {
			return STATUS_OVER;
		}
		if (closed) {
			return WITHIN;
		}

		return WILL_EXCEED.equals(forecastStatus) ? WILL_EXCEED : WITHIN;
	}

	public static final String STATUS_OVER = "OVER";

	// Actual đã vượt Fluct (chế độ OVER)
	public boolean alreadyOver() {
		return MODE_OVER.equals(mode);
	}

	// 2 cách dự báo trái chiều so với Fluct (1 cách vượt, cách kia không); thiếu số -> false
	public boolean projectionsDisagree() {

		Double e = estimateOver();
		Double p = projectedOver();

		if (e == null || p == null) {
			return false;
		}

		return (Math.round(e * 10) > 0) != (Math.round(p * 10) > 0);
	}

	private static Double pctOf(Double value, Double base) {

		Double v = CmText.r1(value);
		Double b = CmText.r1(base);

		if (v == null || b == null || b <= 0) {
			return null;
		}

		return v / b * 100;
	}

	private static Double overPct(Double pctOfFluct) {

		Double pct = CmText.r1(pctOfFluct);

		return pct == null ? null : CmText.r1(pct - 100);
	}

	// Dự báo cuối tháng theo tốc độ chi hiện tại (= "Nếu giữ tốc độ hiện tại"); tháng đã kết thúc -> null
	public Double projectedEom() {
		return linearForecast;
	}

	// projectedEom − Fluct (từ số đã làm tròn 1 chữ số); dương = dự báo vượt Fluct
	public Double projectedOver() {
		return linearVsFluct;
	}

	// projectedEom − FC (từ số đã làm tròn 1 chữ số); dương = dự báo vượt FC
	public Double projectedOverFc() {
		return linearVsFc;
	}

	// projectedEom / Fluct × 100 (từ số đã làm tròn 1 chữ số, khớp số K$ hiển thị); dùng để xếp hạng
	// "khung / bộ phận nào … nhất". Thiếu dự báo hoặc Fluct ≤ 0 -> null
	public Double projectedPctOfFluct() {
		return pctOf(linearForecast, fluct);
	}

	// Xếp hạng theo dự báo (tốc độ chi hiện tại) bằng % Fluct (làm tròn 1 chữ số như hiển thị) giảm dần;
	// bằng nhau -> vượt Fluct (K$) lớn hơn trước; thiếu số ở cuối
	public static final java.util.Comparator<CostMonitoringAssessment> BY_PROJECTED_PCT_DESC =
			java.util.Comparator.comparing((CostMonitoringAssessment a) -> CmText.r1(a.projectedPctOfFluct()),
							java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
					.thenComparing(CostMonitoringAssessment::projectedOver,
							java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));

	// Như trên theo định nghĩa chính (estimate)
	public static final java.util.Comparator<CostMonitoringAssessment> BY_ESTIMATE_PCT_DESC =
			java.util.Comparator.comparing((CostMonitoringAssessment a) -> CmText.r1(a.estimatePctOfFluct()),
							java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
					.thenComparing(CostMonitoringAssessment::estimateOver,
							java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));

	// "Vượt Fluct %" = (% Fluct đã làm tròn 1 chữ số) − 100, để "bằng 125.3% Fluct" khớp "+25.3%". Thiếu -> null
	public Double projectedOverPct() {
		return overPct(projectedPctOfFluct());
	}

	// Nhãn hiển thị cho model: xem CmText (không gửi các mã ở đây ra ngoài)
}
