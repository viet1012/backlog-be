package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.api.dto.AssistantCard;
import com.example.backlogbe.assistant.core.api.dto.AssistantChart;
import com.example.backlogbe.assistant.core.api.dto.AssistantChart.Series;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// BIỂU ĐỒ KÈM CÂU TRẢ LỜI COST MONITORING (ScreenModule.answerCharts)
//
// Số lấy thẳng từ snapshot (cùng nguồn với câu trả lời template / khối KẾT LUẬN), không do LLM sinh.
// Chọn loại chart theo intent (phần đầu trước "+", bỏ hậu tố "_current_rate"):
//   remaining_budget                       -> hbar: đang chi / ngày vs tối đa / ngày (= (Fluct − Actual) / ngày còn lại)
//   top_day, spike_days (1 khung)          -> bar : chi từng ngày + mốc nhịp Fluct / ngày
//   trend, when_exceed*, why, frame…       -> line: lũy kế Actual / Estimate / Fluct / FC theo ngày (≤ 2 khung);
//                                             nhiều khung hơn -> hbar dự báo % Fluct
//   overview, forecast_*, ranking, compare,
//   red_frames, total_forecast…           -> hbar: dự báo cuối tháng (tháng đã kết thúc: Actual) bằng % Fluct
// Intent về tháng trước / khoảng tháng / vật tư -> không vẽ.
// Khung: CostMonitoringLookupAnswers.frames (cùng cách chọn khung với câu trả lời tra cứu).
// =========================================================
@Component
@RequiredArgsConstructor
public class CostMonitoringChartBuilder {

	// Tối đa số chart đường (mỗi khung 1 chart) trong 1 câu trả lời
	static final int MAX_LINE_CHARTS = 2;

	private static final Set<String> BUDGET = Set.of(CostMonitoringLookupAnswers.REMAINING_BUDGET);

	private static final Set<String> DAILY = Set.of(
			CostMonitoringLookupAnswers.TOP_DAY,
			CostMonitoringLookupAnswers.SPIKE_DAYS);

	private static final Set<String> LINE = Set.of(
			CostMonitoringLookupAnswers.TREND,
			CostMonitoringLookupAnswers.WHEN_EXCEED,
			CostMonitoringLookupAnswers.WHEN_EXCEED_FC,
			CostMonitoringLookupAnswers.WHEN_EXCEED_PL,
			CostMonitoringLookupAnswers.WHY_FRAME,
			CostMonitoringLookupAnswers.FRAME_STATUS,
			CostMonitoringLookupAnswers.FRAME_VALUE,
			"why",
			"frame",
			"forecast_one");

	private static final Set<String> FORECAST = Set.of(
			"overview",
			"forecast_all",
			"ranking",
			"compare",
			CostMonitoringLookupAnswers.ALREADY_OVER,
			CostMonitoringLookupAnswers.ANOMALY_TODAY,
			CostMonitoringManagerAnswers.RED_FRAMES,
			CostMonitoringManagerAnswers.TOTAL,
			CostMonitoringManagerAnswers.ATTENTION,
			CostMonitoringManagerAnswers.SUMMARY,
			CostMonitoringManagerAnswers.SHORT,
			CostMonitoringManagerAnswers.DIV_RANK);

	private final CostMonitoringSnapshotLoader loader;

	public List<AssistantChart> build(String intent, String question, Map<String, String> params, String lang) {

		String base = baseIntent(intent);

		if (base == null) {
			return List.of();
		}

		boolean budget = BUDGET.contains(base);
		boolean daily = DAILY.contains(base);
		boolean line = LINE.contains(base);
		boolean forecast = FORECAST.contains(base);

		if (!budget && !daily && !line && !forecast) {
			return List.of();
		}

		Map<String, String> safeParams = params != null ? params : Map.of();
		CostMonitoringSnapshot snapshot = loader.load(safeParams);

		if (snapshot == null || snapshot.d() < 1) {
			return List.of();
		}

		boolean otActual = CostMonitoringLookupAnswers.otHasActual(snapshot);
		List<Frame> frames = frames(snapshot, CostMonitoringLookupAnswers.frames(question, safeParams, otActual));

		if (frames.isEmpty()) {
			return List.of();
		}

		if (budget) {
			AssistantChart chart = snapshot.monthClosed() ? null : budgetChart(snapshot, frames, lang);
			return chart == null ? List.of() : List.of(chart);
		}

		if (daily) {
			AssistantChart chart = frames.size() == 1 ? dailyChart(snapshot, frames.get(0), lang) : null;
			return chart == null ? List.of() : List.of(chart);
		}

		if (line && frames.size() <= MAX_LINE_CHARTS) {
			List<AssistantChart> charts = new ArrayList<>();
			for (Frame fr : frames) {
				AssistantChart chart = lineChart(snapshot, fr, lang);
				if (chart != null) {
					charts.add(chart);
				}
			}
			return charts;
		}

		// "Hôm nay có gì bất thường": chữ xếp khung theo K$ vượt -> chart cùng thước đo / thứ tự
		boolean byAmount = intent.toLowerCase(Locale.ROOT).contains(CostMonitoringLookupAnswers.ANOMALY_TODAY);
		AssistantChart chart = frames.size() >= 2 ? forecastChart(snapshot, frames, lang, byAmount) : null;
		return chart == null ? List.of() : List.of(chart);
	}

	// =========================================================
	// THẺ TÓM TẮT (1 khung): câu kết luận + chip số chính + thanh ngân sách + cầu nối Fluct → FC → Dự báo
	// Chỉ intent 1 khung (LINE: vì sao, tình hình, xu hướng, khi nào vượt, dự báo 1 khung…), tháng chưa kết thúc.
	// =========================================================

	public AssistantCard buildCard(String intent, String question, Map<String, String> params, String lang) {

		String base = baseIntent(intent);

		if (base == null || !LINE.contains(base)) {
			return null;
		}

		Map<String, String> safeParams = params != null ? params : Map.of();
		CostMonitoringSnapshot snapshot = loader.load(safeParams);

		if (snapshot == null || snapshot.d() < 1 || snapshot.monthClosed()) {
			return null;
		}

		boolean otActual = CostMonitoringLookupAnswers.otHasActual(snapshot);
		List<Frame> frames = frames(snapshot, CostMonitoringLookupAnswers.frames(question, safeParams, otActual));

		if (frames.size() != 1 || frames.get(0).a().act() == null) {
			return null;
		}

		return card(snapshot, frames.get(0), lang);
	}

	static AssistantCard card(CostMonitoringSnapshot snapshot, Frame fr, String lang) {

		CostMonitoringAssessment a = fr.a();
		Double act = CmText.r1(a.act());
		Double fluct = CmText.r1(a.fluct());
		Double fc = a.fc() != null ? CmText.r1(a.fc()) : null;
		Double est = a.estimateEom() != null ? CmText.r1(a.estimateEom()) : null;

		boolean over = a.alreadyOver();
		boolean forecastOver = a.estimateWillExceed();
		String tone = over || forecastOver ? AssistantCard.OVER
				: a.excessVsPace() != null && a.excessVsPace() > 0 ? AssistantCard.WATCH : AssistantCard.OK;

		// Câu kết luận
		String headline;
		if (over) {
			headline = t(lang, "Đã vượt Fluct **", "Already above Fluct by **") + CmText.signedK(CmText.subR1(act, fluct)) + "**";
		} else if (forecastOver) {
			headline = t(lang, "Dự báo cuối tháng vượt Fluct **", "Forecast to exceed Fluct by **") + CmText.signedK(a.estimateOver())
					+ t(lang, "**", "** at month-end");
		} else if (a.estimateOver() != null) {
			headline = t(lang, "Dự báo trong Fluct, còn dư **", "Forecast within Fluct, **")
					+ CmText.k1(-a.estimateOver()) + t(lang, "**", "** headroom");
		} else {
			headline = t(lang, "Đã dùng ", "Used ") + CmText.pct1(a.pct()) + " Fluct";
		}

		// Chip: vượt -> tách target bị hạ / vượt FC; trong Fluct -> ngân sách / ngày
		List<AssistantCard.Chip> chips = new ArrayList<>();
		Double end = over ? act : est;
		Double targetCut = fc != null ? CmText.subR1(fc, fluct) : null;
		Double vsFc = fc != null && end != null ? CmText.subR1(end, fc) : null;

		if ((over || forecastOver) && targetCut != null && vsFc != null) {
			if (Math.round(targetCut * 10) > 0) {
				chips.add(new AssistantCard.Chip(t(lang, "Target bị hạ", "Target cut"), CmText.signedK(targetCut), AssistantCard.OVER));
			} else if (Math.round(targetCut * 10) < 0) {
				chips.add(new AssistantCard.Chip(t(lang, "Target được nâng", "Target raised"), CmText.signedK(-targetCut), AssistantCard.OK));
			}
			chips.add(new AssistantCard.Chip(vsFc > 0 ? t(lang, "Vượt FC", "Above FC") : t(lang, "Dưới FC", "Below FC"),
					CmText.signedK(vsFc), vsFc > 0 ? AssistantCard.OVER : AssistantCard.OK));
		} else if (a.remainingPerDay() != null && a.remainingPerDay() > 0) {
			chips.add(new AssistantCard.Chip(t(lang, "Tối đa / ngày", "Max / day"), CmText.rate(a.remainingPerDay(), lang),
					AssistantCard.OK));
		}
		if (a.currentRate() != null) {
			boolean fast = a.remainingPerDay() != null && a.currentRate() > a.remainingPerDay();
			chips.add(new AssistantCard.Chip(t(lang, "Đang chi", "Spending"), CmText.rate(a.currentRate(), lang),
					fast ? AssistantCard.OVER : "neutral"));
		}

		AssistantCard.Budget budget = new AssistantCard.Budget(
				act,
				over ? null : est,
				fluct,
				fc,
				t(lang, "Actual đến ngày ", "Actual to day ") + Math.max(1, snapshot.lastActualDay(fr.chart())),
				t(lang, "Dự báo phần còn lại", "Forecast remainder"),
				t(lang, "Dự báo cuối tháng", "Month-end forecast"));

		// Cầu nối: Fluct → (± target) → FC → (± so với FC) → Dự báo / Actual; target ≈ FC thì không cần
		List<AssistantCard.Step> bridge = new ArrayList<>();
		if (fc != null && end != null && targetCut != null && Math.round(targetCut * 10) != 0) {
			bridge.add(new AssistantCard.Step("Fluct", fluct, AssistantCard.Step.BASE));
			bridge.add(new AssistantCard.Step(targetCut > 0 ? t(lang, "Target bị hạ", "Target cut") : t(lang, "Target được nâng", "Target raised"),
					targetCut, AssistantCard.Step.DELTA));
			bridge.add(new AssistantCard.Step("FC", fc, AssistantCard.Step.TOTAL));
			bridge.add(new AssistantCard.Step(vsFc > 0 ? t(lang, "Vượt FC", "Above FC") : t(lang, "Dưới FC", "Below FC"),
					vsFc, AssistantCard.Step.DELTA));
			bridge.add(new AssistantCard.Step(over ? "Actual" : t(lang, "Dự báo", "Forecast"), end, AssistantCard.Step.TOTAL));
		}

		return new AssistantCard(tone, fr.name(), headline, chips, budget, bridge, "K$");
	}

	// "overview+anomaly_today" -> overview; "forecast_one_current_rate" -> forecast_one;
	// tháng trước / khoảng tháng (intent chứa "prev" / "previous" / "range") -> null
	static String baseIntent(String intent) {

		if (intent == null || intent.isBlank()) {
			return null;
		}

		String value = intent.trim().toLowerCase(Locale.ROOT);

		if (value.contains("prev") || value.startsWith("range")) {
			return null;
		}

		int plus = value.indexOf('+');
		if (plus > 0) {
			value = value.substring(0, plus);
		}

		return value.endsWith("_current_rate") ? value.substring(0, value.length() - "_current_rate".length()) : value;
	}


	// =========================================================
	// KHUNG
	// =========================================================

	// stale: loại chi phí có ngày actual cuối chậm ≥ CostMonitoringLookupAnswers.STALE_DAYS so với D (vd OT nạp file riêng)
	record Frame(String name, String cost, CostChartDto chart, CostMonitoringAssessment a, int lastDay, boolean stale) {
	}

	// Bỏ khung không có chart / Fluct (vd OT chưa nạp)
	private static List<Frame> frames(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> focus) {

		record Raw(CostMonitoringFocus fr, CostChartDto chart, CostMonitoringAssessment a) {
		}

		List<Raw> raws = new ArrayList<>();
		Map<String, Integer> costLastDay = new HashMap<>();

		for (CostMonitoringFocus fr : focus) {
			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			if (chart == null) {
				continue;
			}
			costLastDay.merge(fr.cost(), snapshot.lastActualDay(chart), Math::max);
			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
			if (a.fluct() == null || a.fluct() <= 0) {
				continue;
			}
			raws.add(new Raw(fr, chart, a));
		}

		List<Frame> frames = new ArrayList<>();
		for (Raw r : raws) {
			int last = costLastDay.getOrDefault(r.fr().cost(), 0);
			boolean stale = last > 0 && snapshot.d() - last >= CostMonitoringLookupAnswers.STALE_DAYS;
			frames.add(new Frame(r.fr().div() + " " + r.fr().cost(), r.fr().cost(), r.chart(), r.a(), last, stale));
		}

		return frames;
	}


	// =========================================================
	// NGÂN SÁCH MỖI NGÀY (cùng cách tính với CostMonitoringLookupAnswers.remainingBudget, ngày lịch)
	// =========================================================

	private static AssistantChart budgetChart(CostMonitoringSnapshot snapshot, List<Frame> frames, String lang) {

		int remainingDays = snapshot.n() - snapshot.d();

		if (remainingDays <= 0) {
			return null;
		}

		List<String> names = new ArrayList<>();
		List<Double> current = new ArrayList<>();
		List<Double> max = new ArrayList<>();
		List<String> status = new ArrayList<>();

		for (Frame fr : frames) {

			Double left = CmText.subR1(fr.a().fluct(), fr.a().act());
			if (left == null) {
				continue;
			}

			int actDay = snapshot.lastActualDay(fr.chart());
			int frameDays = actDay > 0 && actDay < snapshot.d() ? snapshot.n() - actDay : remainingDays;
			double perDay = left <= 0 ? 0 : left / frameDays;
			Double rate = fr.a().currentRate();

			names.add(fr.name());
			max.add(CmText.r2(perDay));
			current.add(rate == null ? null : CmText.r2(rate));
			status.add(rate == null ? null : rate > perDay ? Series.OVER : Series.OK);
		}

		if (names.isEmpty()) {
			return null;
		}

		String unit = t(lang, "K$/ngày", "K$/day");

		return new AssistantChart(
				AssistantChart.HBAR,
				t(lang, "Đang chi vs tối đa được chi mỗi ngày", "Current vs maximum spending per day"),
				unit,
				names,
				List.of(
						new Series(t(lang, "Đang chi / ngày", "Current / day"), Series.ACTUAL, current, status),
						new Series(t(lang, "Tối đa / ngày (giữ trong Fluct)", "Max / day (stay within Fluct)"), Series.LIMIT, max)
				),
				null,
				null,
				t(lang, "Tối đa / ngày = (Fluct − Actual) / ngày lịch còn lại. Đỏ = đang chi vượt mức tối đa.",
						"Max / day = (Fluct − Actual) / calendar days left. Red = spending above the maximum.")
		);
	}


	// =========================================================
	// CHI TỪNG NGÀY (1 khung): chênh Act MTD giữa 2 ngày có actual liên tiếp
	// =========================================================

	private static AssistantChart dailyChart(CostMonitoringSnapshot snapshot, Frame fr, String lang) {

		Map<Integer, Double> mtd = actualByDay(snapshot, fr.chart());

		if (mtd.isEmpty()) {
			return null;
		}

		int last = mtd.keySet().stream().max(Integer::compare).orElse(0);
		double pace = fr.a().fluct() / snapshot.n();

		List<String> days = new ArrayList<>();
		List<Double> amounts = new ArrayList<>();
		List<String> status = new ArrayList<>();
		Double prev = 0.0;

		for (int day = 1; day <= last; day++) {
			Double value = mtd.get(day);
			days.add(String.valueOf(day));
			if (value == null) {
				amounts.add(null);
				status.add(null);
				continue;
			}
			double amount = CmText.r1(value - prev);
			prev = value;
			amounts.add(amount);
			status.add(amount > pace ? Series.OVER : Series.OK);
		}

		return new AssistantChart(
				AssistantChart.BAR,
				t(lang, fr.name() + " – chi từng ngày tháng " + snapshot.month(), fr.name() + " – daily spending " + snapshot.month()),
				"K$",
				days,
				List.of(new Series(t(lang, "Chi trong ngày", "Daily spending"), Series.ACTUAL, amounts, status)),
				CmText.r2(pace),
				t(lang, "Nhịp Fluct / ngày", "Fluct pace / day"),
				t(lang, "Đỏ = ngày chi cao hơn nhịp Fluct / ngày (Fluct / số ngày trong tháng).",
						"Red = day above the Fluct daily pace (Fluct / days in month).")
		);
	}


	// =========================================================
	// LŨY KẾ THEO NGÀY (1 khung): Actual, Estimate (sau ngày actual cuối), Fluct, FC
	// =========================================================

	private static AssistantChart lineChart(CostMonitoringSnapshot snapshot, Frame fr, String lang) {

		Map<Integer, Double> actual = actualByDay(snapshot, fr.chart());

		if (actual.isEmpty()) {
			return null;
		}

		int n = snapshot.n();
		int lastAct = actual.keySet().stream().max(Integer::compare).orElse(0);
		boolean withEstimate = !snapshot.monthClosed();

		Map<Integer, CostChartPointDto> points = pointsByDay(snapshot.month(), fr.chart());

		List<String> days = new ArrayList<>();
		List<Double> act = new ArrayList<>();
		List<Double> est = new ArrayList<>();
		List<Double> fluct = new ArrayList<>();
		List<Double> fc = new ArrayList<>();
		boolean anyEstimate = false;
		boolean anyFluct = false;

		for (int day = 1; day <= n; day++) {
			CostChartPointDto p = points.get(day);
			days.add(String.valueOf(day));
			act.add(day <= lastAct ? CmText.r1(actual.get(day)) : null);

			Double e = null;
			if (withEstimate) {
				if (day == lastAct) {
					// nối Estimate vào điểm Actual cuối (đường liền mạch)
					e = CmText.r1(actual.get(day));
				} else if (day > lastAct && p != null && p.estMtd() != null) {
					e = CmText.r1(p.estMtd());
					anyEstimate = true;
				}
			}
			est.add(e);

			Double fl = p != null ? CmText.r1(p.fluctTgt()) : null;
			anyFluct |= fl != null;
			fluct.add(fl);
			fc.add(fr.a().fc() != null ? CmText.r1(fr.a().fc()) : null);
		}

		List<Series> series = new ArrayList<>();
		series.add(new Series("Actual", Series.ACTUAL, act));
		if (anyEstimate) {
			series.add(new Series(t(lang, "Dự báo (Estimate)", "Estimate"), Series.ESTIMATE, est));
		}
		if (anyFluct) {
			series.add(new Series("Fluct", Series.TARGET, fluct));
		}
		if (fr.a().fc() != null) {
			series.add(new Series("FC", Series.FC, fc));
		}

		String note;
		if (snapshot.monthClosed()) {
			note = t(lang, "Tháng đã kết thúc: Actual cả tháng ", "Month closed: full-month Actual ") + CmText.k1(fr.a().act())
					+ t(lang, " / Fluct ", " / Fluct ") + CmText.k1(fr.a().fluct()) + ".";
		} else if (fr.a().estimateEom() != null) {
			note = t(lang, "Dự báo cuối tháng ", "Month-end forecast ") + CmText.k1(fr.a().estimateEom())
					+ (fr.a().estimatePctOfFluct() != null
					? t(lang, " (bằng ", " (") + CmText.pct1(fr.a().estimatePctOfFluct()) + t(lang, " Fluct)", " of Fluct)") : "")
					+ t(lang, "; actual đến ngày ", "; actual up to day ") + lastAct + ".";
		} else {
			note = t(lang, "Actual đến ngày ", "Actual up to day ") + lastAct + ".";
		}

		return new AssistantChart(
				AssistantChart.LINE,
				t(lang, fr.name() + " – lũy kế tháng " + snapshot.month(), fr.name() + " – month to date " + snapshot.month()),
				"K$",
				days,
				series,
				null,
				null,
				note
		);
	}


	// =========================================================
	// DỰ BÁO CUỐI THÁNG BẰNG % FLUCT (nhiều khung), xếp giảm dần
	// =========================================================

	// byAmount = true ("Hôm nay có gì bất thường" – chữ xếp theo K$ vượt): giá trị = dự báo − Fluct (K$), xếp giảm dần,
	//   để thứ tự chart khớp danh sách trong câu trả lời. false: % Fluct, mốc 100%.
	// Khung dữ liệu cũ (Frame.stale) -> status STALE (FE tô xám) + ghi chú.
	private static AssistantChart forecastChart(CostMonitoringSnapshot snapshot, List<Frame> frames, String lang, boolean byAmount) {

		boolean closed = snapshot.monthClosed();
		boolean amount = byAmount && !closed;

		record Row(String name, double value, boolean stale) {
		}

		List<Row> rows = new ArrayList<>();

		for (Frame fr : frames) {
			Double base = closed ? fr.a().act() : fr.a().estimateEom();
			if (base == null) {
				continue;
			}
			Double value = amount ? fr.a().estimateOver() : CmText.r1(base / fr.a().fluct() * 100);
			if (value == null) {
				continue;
			}
			rows.add(new Row(fr.name(), value, fr.stale()));
		}

		if (rows.size() < 2) {
			return null;
		}

		rows.sort(Comparator.comparingDouble(Row::value).reversed());

		double limit = amount ? 0 : 100;
		List<String> names = rows.stream().map(Row::name).toList();
		List<Double> values = rows.stream().map(r -> (Double) r.value()).toList();
		List<String> status = rows.stream()
				.map(r -> r.stale() ? Series.STALE : r.value() > limit ? Series.OVER : Series.OK)
				.toList();

		String staleNote = staleNote(frames, rows.stream().anyMatch(Row::stale), lang);

		if (amount) {
			return new AssistantChart(
					AssistantChart.HBAR,
					t(lang, "Dự báo cuối tháng " + snapshot.month() + " so với Fluct (K$)",
							"Month-end forecast " + snapshot.month() + " vs Fluct (K$)"),
					"K$",
					names,
					List.of(new Series(t(lang, "Dự báo − Fluct", "Forecast − Fluct"), Series.ESTIMATE, values, status)),
					null,
					null,
					t(lang, "Dương = dự báo vượt Fluct, âm = còn dư. Dự báo chính (theo PL; không có PL thì theo nhịp Fluct).",
							"Positive = forecast above Fluct, negative = headroom. Main forecast (PL-based; Fluct pace when no PL).")
							+ staleNote
			);
		}

		return new AssistantChart(
				AssistantChart.HBAR,
				closed
						? t(lang, "Actual cả tháng " + snapshot.month() + " (% Fluct)", "Full-month Actual " + snapshot.month() + " (% of Fluct)")
						: t(lang, "Dự báo cuối tháng " + snapshot.month() + " (% Fluct)", "Month-end forecast " + snapshot.month() + " (% of Fluct)"),
				"%",
				names,
				List.of(new Series(closed ? "Actual" : t(lang, "Dự báo", "Forecast"), Series.ESTIMATE, values, status)),
				100.0,
				"Fluct",
				(closed
						? t(lang, "100% = Fluct.", "100% = Fluct.")
						: t(lang, "Dự báo chính (theo PL; không có PL thì theo nhịp Fluct). 100% = Fluct.",
						"Main forecast (PL-based; Fluct pace when no PL). 100% = Fluct."))
						+ staleNote
		);
	}

	// " Xám = OT chỉ có số đến 10/11 (chậm 9 ngày), kém tin cậy." – không có khung cũ -> ""
	private static String staleNote(List<Frame> frames, boolean shown, String lang) {

		if (!shown) {
			return "";
		}

		Map<String, Integer> lastDays = new java.util.LinkedHashMap<>();
		for (Frame fr : frames) {
			if (fr.stale()) {
				lastDays.merge(fr.cost(), fr.lastDay(), Math::max);
			}
		}

		List<String> parts = lastDays.entrySet().stream()
				.map(e -> e.getKey() + t(lang, " chỉ có số đến ngày ", " only up to day ") + e.getValue())
				.toList();

		return t(lang, " Xám = dữ liệu cũ (", " Grey = stale data (") + String.join(", ", parts)
				+ t(lang, "), kém tin cậy.", "), less reliable.");
	}


	// =========================================================
	// HELPERS
	// =========================================================

	// Ngày trong tháng -> Act MTD (chỉ ngày có actual)
	private static Map<Integer, Double> actualByDay(CostMonitoringSnapshot snapshot, CostChartDto chart) {

		Map<Integer, Double> result = new HashMap<>();

		for (CostChartPointDto p : chart.points()) {
			if (p.date() != null && p.actMtd() != null && YearMonth.from(p.date()).equals(snapshot.month())) {
				result.put(p.date().getDayOfMonth(), p.actMtd());
			}
		}

		return result;
	}

	private static Map<Integer, CostChartPointDto> pointsByDay(YearMonth month, CostChartDto chart) {

		Map<Integer, CostChartPointDto> result = new HashMap<>();

		for (CostChartPointDto p : chart.points()) {
			LocalDate date = p.date();
			if (date != null && YearMonth.from(date).equals(month)) {
				result.put(date.getDayOfMonth(), p);
			}
		}

		return result;
	}
}
