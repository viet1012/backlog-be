package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository.DailyRatio;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartForecastDto;
import com.example.backlogbe.dto.costmonitoring.CostPlWeeksResponse;
import com.example.backlogbe.service.CostMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// CONTEXT: COST MONITORING
//
// Gọi lại CostMonitoringService.getChart (không query DB riêng). Gồm:
//   Mọi nhãn theo lang (vi / en) qua CmText; KHÔNG gửi mã nội bộ cho model.
//   Header : tháng đang xem, "Trạng thái tháng: đang diễn ra / đã kết thúc" (snapshot.monthClosed()),
//            "PL TGT: Tuần x" / "chưa có simulation…" (xem plTgtLine),
//   Tháng đã kết thúc: tổng hợp + mỗi khung chỉ có Badge, "Kết quả tháng", "Chênh so với PL"
//            (không Estimate / tiến độ / dự báo / rủi ro / kịch bản).
//            D (ngày có dữ liệu mới nhất), N (số ngày tháng), hôm nay
//   Mỗi khung 1 dòng (vi):
//     "PRESS T&SL: FC=…K$|PL=…|Fluct=…|Actual=…|Estimate=…|Badge=OK 19%|Tiến độ chuẩn=16%
//      |Tiến độ=Chi nhanh hơn tiến độ|Dự báo cuối tháng=221.3 K$ (theo PL)
//      |Kết luận dự báo=Dự báo vượt Fluct +34.9 K$|Rủi ro=Rủi ro cao
//      |Nếu từ nay chi đúng target: …|Nếu giữ tốc độ hiện tại: …|Ngân sách còn lại mỗi ngày=…"
//     Kịch bản: CmText.scenarioParts (tính trong CostMonitoringAssessment, so với Fluct và FC có dấu).
//     Badge khớp FE: "OK x%" (Act ≤ Fluct) / "Vượt x%" / "Chưa có actual" / "Chưa có target",
//       x = Act / Fluct × 100 làm tròn số nguyên; so sánh bằng giá trị chưa làm tròn.
//     Tiến độ / Dự báo / Rủi ro: tính trong CostMonitoringAssessment
//       (Forecast theo PL = Est_MTD cuối tháng của SP; không có PL = Act + Fluct / N × (N − D)).
//   Số làm tròn 1 chữ số; giá trị không có -> để trống.
//
//   Mỗi bộ phận 1 dòng: "<DIV> Ratio mới nhất = x% | KVH = y%" (F2_Working_Date.Adj_Ratio_*, xem appendRatios).
//   Doanh số / tồn kho chi tiết: chưa có nguồn -> "chưa có dữ liệu".
//
// Dùng qua CostMonitoringModule (ScreenModule). Tool: xem CostMonitoringModule.tools().
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class CostMonitoringContextProvider {

	static final String STATUS_NO_ACTUAL = "NO_ACTUAL";
	static final String STATUS_NO_TARGET = "NO_TARGET";
	static final String STATUS_OVER = "OVER";
	static final String STATUS_OK = "OK";

	private final CostMonitoringSnapshotLoader loader;
	private final CostMonitoringService costMonitoringService;
	private final CostWorkingDateRepository workingDateRepository;

	public String buildContext(Map<String, String> params, String lang) {
		return buildContext(params, lang, null);
	}

	/*
	 * question != null và xác định được đúng 1 khung (CostMonitoringQuestion.frame: câu hỏi, phần thiếu lấy từ
	 * ô drawer) -> context THU GỌN để giảm token: chỉ dòng của khung đó + Ratio bộ phận đó, thay 5 dòng tổng hợp
	 * bằng 1 dòng ghi chú (khung khác: gọi tool). Không xác định được / khung không có trong snapshot -> đầy đủ.
	 */
	public String buildContext(Map<String, String> params, String lang, String question) {

		CostMonitoringSnapshot snapshot = loader.load(params);

		CostMonitoringFocus frame = question == null ? null : CostMonitoringQuestion.frame(question, params);
		CostChartDto frameChart = frame == null ? null : snapshot.find(frame.div(), frame.cost());
		String simuWeek = snapshot.response().simuWeek();
		CostPlWeeksResponse plWeeks = costMonitoringService.getPlWeeks(snapshot.response().month());

		int d = snapshot.d();
		int n = snapshot.n();
		boolean closed = snapshot.monthClosed();

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "Tháng đang xem trên màn hình: ", "Month shown on screen: "))
				.append(snapshot.month()).append('\n');
		sb.append(t(lang, "Trạng thái tháng: ", "Month status: "))
				.append(CmText.monthStatus(closed, lang));
		if (closed) {
			sb.append(t(lang,
					" (chỉ có kết quả tháng; không có tiến độ, dự báo, rủi ro)",
					" (month result only; no pace, forecast or risk)"));
		}
		sb.append('\n');
		sb.append(plTgtLine(simuWeek, plWeeks, lang)).append('\n');
		appendFocus(sb, params, snapshot, lang);

		sb.append(t(lang, "Ngày có dữ liệu mới nhất (D)=", "Latest data day (D)=")).append(d)
				.append(t(lang, "|Số ngày trong tháng (N)=", "|Days in month (N)=")).append(n)
				.append(t(lang, "|Hôm nay=", "|Today=")).append(snapshot.today())
				.append(t(lang, "|Đơn vị=K$", "|Unit=K$"))
				.append('\n');

		if (!snapshot.dFromActual()) {
			sb.append(t(lang,
					"Chưa có Actual trong tháng; D tính theo số ngày đã qua.",
					"No actual yet this month; D counts calendar days passed.")).append('\n');
		}

		if (frameChart == null) {
			appendSummary(sb, snapshot, d, n, lang);
		} else {
			String name = frame.div() + " " + frame.cost();
			sb.append(t(lang,
							"Chỉ gửi dữ liệu khung " + name + " (câu hỏi về 1 khung). Khung khác hoặc tổng hợp cả tháng: "
									+ "gọi explain_variance (nhiều bộ phận: div=ALL).",
							"Only data for " + name + " is included (question about 1 chart). Other charts or month "
									+ "summary: call explain_variance (several divisions: div=ALL)."))
					.append('\n');
		}

		// Mỗi khung 1 dòng: "PRESS T&SL: FC=…|PL=…|Fluct=…|Actual=…|Estimate=…|Badge=…|…"
		for (CostChartDto chart : snapshot.response().charts()) {

			if (frameChart != null && chart != frameChart) {
				continue;
			}

			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);

			sb.append(CostMonitoringSnapshot.divName(chart.div())).append(' ').append(chart.chart()).append(": ")
					.append("FC=").append(CmText.k1(chart.fcTgt()))
					.append("|PL=").append(CmText.k1(chart.plTgt()))
					.append("|Fluct=").append(CmText.k1(a.fluct()))
					.append("|Actual=").append(CmText.k1(a.act()));

			// Tháng đã kết thúc: Kết quả tháng thay cho Estimate / tiến độ / dự báo / rủi ro / kịch bản
			if (closed) {
				sb.append("|Badge=").append(badgeText(a, lang))
						.append(t(lang, "|Kết quả tháng=", "|Month result=")).append(CmText.monthResult(a, lang))
						.append(t(lang, "|Chênh so với PL=", "|Difference vs PL="))
						.append(CmText.signedK(CmText.subR1(a.act(), chart.plTgt()),
								t(lang, "cao hơn PL", "above PL"),
								t(lang, "thấp hơn PL", "below PL")))
						.append('\n');
				continue;
			}

			Double estimate = CostMonitoringSnapshot.estimate(chart);
			sb.append("|Estimate=").append(estimate == null ? "-" : CmText.k1(estimate)
							+ (CostMonitoringSnapshot.estimateByFc(chart) ? " (" + CmText.forecastBase("FC", lang) + ")" : ""));

			// Ngày Estimate (MTD) vượt Fluct của chính ngày đó (tính sẵn; model không tự dò chuỗi ngày)
			String cross = CmText.estimateCrossText(chart, lang);
			if (cross != null) {
				sb.append(t(lang, "|Ngày dự kiến vượt Fluct (theo Estimate)=", "|Expected day to exceed Fluct (by Estimate)="))
						.append(cross);
			}

			sb.append("|Badge=").append(badgeText(a, lang))
					.append(t(lang, "|Tiến độ chuẩn=", "|Standard pace=")).append(CmText.pct0(a.pace()))
					.append(t(lang, "|Tiến độ=", "|Pace=")).append(CmText.pace(a.paceStatus(), lang))
					.append(t(lang, "|Dự báo cuối tháng=", "|Month-end forecast=")).append(forecastText(a, lang))
					.append(t(lang, "|Kết luận dự báo=", "|Forecast outlook=")).append(CmText.forecastOutlook(a, lang))
					.append(t(lang, "|Rủi ro=", "|Risk=")).append(CmText.risk(a.risk(), lang));

			// 2 kịch bản cuối tháng + ngân sách còn lại mỗi ngày (CmText.scenarioParts)
			for (String part : CmText.scenarioParts(a, lang)) {
				sb.append('|').append(part);
			}

			sb.append('\n');
		}

		appendRatios(sb, snapshot, frameChart != null ? frame.div() : null, lang);
		sb.append(t(lang, "Giá trị trống = chưa có dữ liệu.", "Empty value = no data."));

		return sb.toString();
	}

	/*
	 * Bản rút gọn cho tháng được hỏi (khối "[Dữ liệu tháng …]"):
	 *   header: "Tháng: YYYY-MM", trạng thái tháng, D, N, PL TGT
	 *   + 3 dòng tổng hợp (tháng đã kết thúc: 2 dòng kết quả tháng)
	 *   + mỗi khung: FC|PL|Fluct|Actual|Badge|Kết luận dự báo|Rủi ro
	 *                (tháng đã kết thúc: FC|PL|Fluct|Actual|Badge|Kết quả tháng)
	 *   + dòng Ratio theo bộ phận.
	 * Không có kịch bản / ngân sách còn lại / Estimate / tiến độ (tool vẫn trả đủ khi gọi với month).
	 * Cả 9 khung đều không có FC và không có Actual (hoặc không có khung) -> null (tháng chưa có dữ liệu).
	 * PL TGT theo tuần Simu mới nhất (simuWeek = null).
	 */
	public String buildCompactContext(YearMonth month, String lang) {

		CostMonitoringSnapshot snapshot = loader.load(month.toString(), null);

		if (!hasData(snapshot)) {
			return null;
		}

		String simuWeek = snapshot.response().simuWeek();
		CostPlWeeksResponse plWeeks = costMonitoringService.getPlWeeks(snapshot.response().month());

		int d = snapshot.d();
		int n = snapshot.n();
		boolean closed = snapshot.monthClosed();

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "Tháng: ", "Month: ")).append(snapshot.month())
				.append(t(lang, "|Trạng thái tháng: ", "|Month status: ")).append(CmText.monthStatus(closed, lang))
				.append('\n');
		sb.append(plTgtLine(simuWeek, plWeeks, lang)).append('\n');
		sb.append(t(lang, "Ngày có dữ liệu mới nhất (D)=", "Latest data day (D)=")).append(d)
				.append(t(lang, "|Số ngày trong tháng (N)=", "|Days in month (N)=")).append(n)
				.append(t(lang, "|Đơn vị=K$", "|Unit=K$"))
				.append('\n');

		// Tháng chỉ có target, chưa khung nào có Actual (vd 2026-03): nói thẳng để model không trả "đã có dữ liệu"
		// (test 2026-11-12 ext-254 "Tháng 3 năm nay có dữ liệu không?" -> "Có, tháng 3/2026 đã có dữ liệu")
		if (snapshot.response().charts().stream().allMatch(c -> c.actMtdLatest() == null)) {
			sb.append(t(lang, "Trạng thái dữ liệu=CHƯA CÓ số chi (Actual) ở mọi khung – chỉ có target (FC / PL / Fluct); "
							+ "trả lời là tháng này chưa có số liệu chi phí thực tế.",
					"Data status=NO Actual spending in any frame – targets only (FC / PL / Fluct); "
							+ "answer that this month has no actual cost data yet.")).append('\n');
		}

		appendSummary(sb, snapshot, d, n, lang);

		for (CostChartDto chart : snapshot.response().charts()) {

			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);

			sb.append(CostMonitoringSnapshot.divName(chart.div())).append(' ').append(chart.chart()).append(": ")
					.append("FC=").append(CmText.k1(chart.fcTgt()))
					.append("|PL=").append(CmText.k1(chart.plTgt()))
					.append("|Fluct=").append(CmText.k1(a.fluct()))
					.append("|Actual=").append(CmText.k1(a.act()))
					.append("|Badge=").append(badgeText(a, lang));

			if (closed) {
				sb.append(t(lang, "|Kết quả tháng=", "|Month result=")).append(CmText.monthResult(a, lang));
			} else {
				sb.append(t(lang, "|Kết luận dự báo=", "|Forecast outlook=")).append(CmText.forecastOutlook(a, lang))
						.append(t(lang, "|Rủi ro=", "|Risk=")).append(CmText.risk(a.risk(), lang));
			}

			sb.append('\n');
		}

		appendRatios(sb, snapshot, null, lang);

		return sb.toString().trim();
	}

	/*
	 * FE mở chat từ drawer của 1 ô (params factory + category): 1 dòng
	 *   "Ô đang xem (mở từ drawer): PRESS T&SL, tháng 2026-10. Câu hỏi không nêu bộ phận / loại chi phí
	 *    thì hiểu là ô này; gọi tool với div=PRESS, cost=T&SL."
	 * Không có focus -> không ghi gì.
	 */
	private static void appendFocus(
			StringBuilder sb,
			Map<String, String> params,
			CostMonitoringSnapshot snapshot,
			String lang
	) {

		CostMonitoringFocus focus = CostMonitoringFocus.from(params);

		if (focus == null) {
			return;
		}

		String name = focus.div() + " " + focus.cost();

		sb.append(t(lang, "Ô đang xem (mở từ drawer): ", "Cell in focus (opened from drawer): "))
				.append(name)
				.append(t(lang, ", tháng ", ", month ")).append(snapshot.month())
				.append(t(lang,
						". Câu hỏi không nêu bộ phận / loại chi phí thì hiểu là ô này; gọi tool với div=",
						". If the question names no division / cost type, it refers to this cell; call tools with div="))
				.append(focus.div()).append(", cost=").append(focus.cost()).append('.')
				.append('\n');
	}

	// Có ít nhất 1 khung có FC hoặc Actual
	private static boolean hasData(CostMonitoringSnapshot snapshot) {

		List<CostChartDto> charts = snapshot.response().charts();

		if (charts == null) {
			return false;
		}

		for (CostChartDto chart : charts) {
			if (chart.fcTgt() != null || chart.actMtdLatest() != null) {
				return true;
			}
		}

		return false;
	}

	/*
	 * Dòng tổng hợp do code tính sẵn (model trả lời câu hỏi chung "có vượt không" theo các dòng này).
	 * Trạng thái CHÍNH giống /chart và /suggestions (CostMonitoringAssessment.mainStatus, CostMonitoringAssessment.of):
	 *   "Dự báo vượt Fluct (chính, theo PL; chưa vượt thực tế): N khung – X (CmText.estimateBrief)"   WILL_EXCEED
	 *   "Dự báo vượt FC nhưng trong Fluct (chính): M khung – …"     chưa vượt, estimateOver ≤ 0, estimateOverFc > 0
	 *   "Đã vượt thực tế: K khung – PRESS T&SL (Vượt 137%)"          OVER
	 *   2 cách dự báo trái chiều -> thêm "; theo tốc độ chi hiện tại thì …" (CmText.projectedNote)
	 * PHỤ (chỉ cho câu "cứ chi như vậy"):
	 *   "Theo tốc độ chi hiện tại (phụ…) – dự báo vượt Fluct: L khung – X (CmText.projectedSummary)"  projectedOver > 0
	 *   Cần theo dõi: Risk = WATCH (chi nhanh hơn tiến độ, dự báo chính vẫn trong target)
	 * Không có khung nào -> "không có".
	 */
	private static void appendSummary(StringBuilder sb, CostMonitoringSnapshot snapshot, int d, int n, String lang) {

		List<String> over = new ArrayList<>();
		List<String> willExceed = new ArrayList<>();
		List<String> watch = new ArrayList<>();

		String none = t(lang, "không có", "none");

		// Tháng đã kết thúc: chỉ 2 dòng kết quả tháng
		if (snapshot.monthClosed()) {

			List<String> within = new ArrayList<>();

			for (CostChartDto chart : snapshot.response().charts()) {

				CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
				String name = CostMonitoringSnapshot.divName(chart.div()) + " " + chart.chart();

				if (CostMonitoringAssessment.MODE_OVER.equals(a.mode())) {
					over.add(name + " (" + CmText.monthResult(a, lang) + ")");
				} else if (CostMonitoringAssessment.MODE_CLOSED_WITHIN.equals(a.mode())) {
					within.add(name + " (" + CmText.monthResult(a, lang) + ")");
				}
			}

			sb.append(t(lang, "Kết quả tháng – vượt Fluct: ", "Month result – over Fluct: "))
					.append(over.isEmpty() ? none : String.join(", ", over)).append('\n');
			sb.append(t(lang, "Kết quả tháng – trong Fluct: ", "Month result – within Fluct: "))
					.append(within.isEmpty() ? none : String.join(", ", within)).append('\n');
			return;
		}

		List<String> estimateOverFcOnly = new ArrayList<>();
		List<String> projectedOverFluct = new ArrayList<>();

		for (CostChartDto chart : snapshot.response().charts()) {

			// Cùng cách tính với /chart, /detail, /suggestions
			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
			String name = CostMonitoringSnapshot.divName(chart.div()) + " " + chart.chart();

			// Ghi chú khi 2 cách dự báo trái chiều
			String note = a.projectionsDisagree() ? "; " + CmText.projectedNote(a, lang) : "";

			// Trạng thái CHÍNH (giống /chart, /suggestions): đã vượt -> dự báo vượt (PL Estimate)
			String status = a.mainStatus();

			if (CostChartForecastDto.STATUS_OVER.equals(status)) {
				over.add(name + " (" + badgeText(a, lang) + note + ")");
			} else if (CostChartForecastDto.STATUS_WILL_EXCEED.equals(status)) {
				willExceed.add(name + " (" + CmText.estimateBrief(a, lang) + note + ")");
			} else if (positive(a.estimateOverFc())) {
				estimateOverFcOnly.add(name + " (" + CmText.estimateBrief(a, lang) + note + ")");
			}

			// PHỤ: theo tốc độ chi hiện tại (chỉ cho câu "cứ chi như vậy")
			if (positive(a.projectedOver())) {
				projectedOverFluct.add(name + " (" + CmText.projectedSummary(a, lang) + ")");
			}

			if (CostMonitoringAssessment.RISK_WATCH.equals(a.risk())) {
				watch.add(name);
			}
		}

		sb.append(t(lang, "Dự báo vượt Fluct (chính: theo PL, chưa có PL thì theo nhịp Fluct; chưa vượt thực tế): ",
						"Forecast above Fluct (main: based on PL, Fluct pace when no PL; not yet over): "))
				.append(countedList(willExceed, none, lang)).append('\n');
		sb.append(t(lang, "Dự báo vượt FC nhưng trong Fluct (chính): ",
						"Forecast above FC but within Fluct (main): "))
				.append(countedList(estimateOverFcOnly, none, lang)).append('\n');
		sb.append(t(lang, "Đã vượt thực tế: ", "Already over target: "))
				.append(countedList(over, none, lang)).append('\n');
		sb.append(t(lang,
						"Theo tốc độ chi hiện tại (phụ, chỉ cho câu \"cứ chi như vậy\") – dự báo vượt Fluct: ",
						"At current spending rate (secondary, only for \"at this rate\" questions) – projected above Fluct: "))
				.append(countedList(projectedOverFluct, none, lang)).append('\n');
		sb.append(t(lang, "Cần theo dõi: ", "Needs watching: "))
				.append(watch.isEmpty() ? none : String.join(", ", watch)).append('\n');
	}

	// Số đã làm tròn 1 chữ số > 0 (cùng cách hiển thị signedK)
	private static boolean positive(Double value) {
		return value != null && Math.round(value * 10) > 0;
	}

	// "2 khung – A (…), B (…)"; rỗng -> "không có"
	private static String countedList(List<String> items, String none, String lang) {

		if (items.isEmpty()) {
			return none;
		}

		return items.size() + t(lang, " khung – ", " charts – ") + String.join(", ", items);
	}

	/*
	 * Mỗi bộ phận 1 dòng: "<DIV> Ratio mới nhất = x% | KVH = y%"
	 *   Ratio = Adj_Ratio_PR/_MO/_GU khác null cuối cùng từ ngày 1 đến min(cuối tháng, hôm qua);
	 *   KVH = Adj_Ratio_KVH khác null cuối cùng trong cùng khoảng. Lỗi đọc -> 1 dòng "chưa có dữ liệu".
	 *   onlyDiv != null -> chỉ dòng của bộ phận đó (context thu gọn 1 khung).
	 */
	private void appendRatios(StringBuilder sb, CostMonitoringSnapshot snapshot, String onlyDiv, String lang) {

		LocalDate from = snapshot.month().atDay(1);
		LocalDate until = RatioTrendTool.lastRatioDay(snapshot);

		TreeMap<LocalDate, DailyRatio> ratios;
		try {
			ratios = until.isBefore(from) ? new TreeMap<>() : workingDateRepository.findRatios(from, until);
		} catch (RuntimeException e) {
			log.warn("cost-monitoring context: cannot load ratios error={}", e.getClass().getSimpleName());
			ratios = new TreeMap<>();
		}

		if (ratios.isEmpty()) {
			sb.append(t(lang, "Ratio theo bộ phận: chưa có dữ liệu.", "Ratio by division: no data.")).append('\n');
		} else {
			Double kvh = latest(ratios, null);
			for (String div : onlyDiv != null ? List.of(onlyDiv) : CostMonitoringSnapshot.DIVS) {
				sb.append(div)
						.append(t(lang, " Ratio mới nhất = ", " Latest ratio = ")).append(RatioTrendTool.pct(latest(ratios, div)))
						.append(" | KVH = ").append(RatioTrendTool.pct(kvh))
						.append('\n');
			}
		}

		sb.append(t(lang,
				"Doanh số và tồn kho chi tiết: chưa có dữ liệu.",
				"Detailed sales and inventory: no data.")).append('\n');
	}

	// Giá trị khác null cuối cùng; div = null -> KVH
	private static Double latest(TreeMap<LocalDate, DailyRatio> ratios, String div) {

		for (DailyRatio r : ratios.descendingMap().values()) {
			Double value = div == null ? r.kvh() : RatioTrendTool.ratioOf(r, div);
			if (value != null) {
				return value;
			}
		}

		return null;
	}

	// "OK 19%" / "Vượt 137%" / "Chưa có actual" / "Chưa có target" (khớp badge FE)
	private static String badgeText(CostMonitoringAssessment a, String lang) {

		String code = status(a.act(), a.fluct(), a.pct());
		String label = CmText.badge(code, lang);

		return STATUS_OK.equals(code) || STATUS_OVER.equals(code)
				? label + " " + CmText.pct0(a.pct())
				: label;
	}

	// "221.3 K$ (theo PL)"
	private static String forecastText(CostMonitoringAssessment a, String lang) {

		String value = CmText.k1(a.forecast());

		if (value.isEmpty()) {
			return "";
		}

		String base = CmText.forecastBase(a.forecastBase(), lang);

		return base.isEmpty() ? value : value + " (" + base + ")";
	}

	/*
	 * Header tuần PL simulation (vi; en tương ứng):
	 *   "PL TGT: Tuần x"                          – chọn tuần cụ thể có số
	 *   "PL TGT: Tuần x (mới nhất)"               – "Mới nhất", mọi khung cùng tuần
	 *   "PL TGT: Tuần x (mới nhất; …tuần cũ hơn)" – "Mới nhất", khung thiếu số dùng tuần cũ hơn
	 *   "PL TGT: Tuần x (tuần này chưa có …)"     – chọn tuần không có số (PL trống)
	 *   "PL TGT: chưa có simulation tháng này"
	 */
	static String plTgtLine(String selectedWeek, CostPlWeeksResponse plWeeks, String lang) {

		if (plWeeks.weeks().isEmpty()) {
			return t(lang, "PL TGT: chưa có simulation tháng này", "PL TGT: no simulation this month");
		}

		if (selectedWeek != null) {
			return plWeeks.weeks().contains(selectedWeek)
					? "PL TGT: " + weekLabel(selectedWeek, lang)
					: "PL TGT: " + weekLabel(selectedWeek, lang)
					+ t(lang, " (tuần này chưa có simulation, PL trống)", " (no simulation for this week, PL empty)");
		}

		return "PL TGT: " + weekLabel(plWeeks.latestWeek(), lang)
				+ (plWeeks.latestMixed()
						? t(lang, " (mới nhất; khung thiếu số tuần này dùng tuần cũ hơn)",
								" (latest; charts missing this week use an older week)")
						: t(lang, " (mới nhất)", " (latest)"));
	}

	// "Simu_W2" -> "Tuần 2" / "Week 2"
	private static String weekLabel(String simuWeek, String lang) {
		return t(lang, "Tuần ", "Week ") + simuWeek.substring(simuWeek.length() - 1);
	}

	// Badge: cùng quy tắc vượt với FE (CostMonitoringAssessment.exceeds, số gốc)
	private static String status(Double act, Double fluct, Double pct) {

		if (act == null) {
			return STATUS_NO_ACTUAL;
		}

		if (pct == null) {
			return STATUS_NO_TARGET;
		}

		return CostMonitoringAssessment.exceeds(act, fluct) ? STATUS_OVER : STATUS_OK;
	}
}
