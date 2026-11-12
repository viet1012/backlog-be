package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.month.QuestionMonthDetector.MonthRange;
import com.example.backlogbe.assistant.core.screen.AnswerGuide;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringLookupAnswers.Dimension;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopItemDto;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostTopItemsRepository;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// CÂU HỎI THEO KHOẢNG NHIỀU THÁNG (template, không gọi LLM)
//
// Khoảng do lõi dò (QuestionMonthDetector.range: "trong 3 tháng", "quý này", "từ tháng 8 đến 10", "từ đầu năm"…),
// ChatOrchestrator gọi CostMonitoringModule.answerGuideForRange. Trước: mọi template tính theo tháng đang xem
// (test 2026-11-11 "tool nào xuất nhiều nhất trong 3 tháng" trả 01/11–10/11).
//
//   range_top_item   : "vật tư nào chi nhiều nhất trong 3 tháng" -> top vật tư theo K$ trên cả khoảng (mỗi khung riêng)
//   range_top_detail : máy / cost center / Category chi nhiều nhất trong khoảng
//   range_summary    : chi phí / tình hình / xu hướng / có vượt … trong khoảng -> từng tháng của từng khung:
//                      tháng đã kết thúc Actual / Fluct (% , vượt / trong Fluct); tháng đang chạy Actual đến mốc + dự báo
//                      cuối tháng (dự báo chính như màn hình); cộng các tháng đã kết thúc; Nhận định tính bằng code
//                      (vượt Fluct lặp lại, xu hướng % Fluct tăng / giảm, khung nặng nhất).
//                      Tháng không khung nào có Actual -> ghi "chưa có dữ liệu" (không bỏ qua im lặng).
//   range_why        : "vì sao … tăng / giảm / vượt" (≤ 2 khung, không hỏi vật tư / máy…) -> bảng từng tháng + yếu tố tính
//                      bằng code: chi (Actual) và target (Fluct) đổi giữa tháng đã kết thúc đầu – cuối khoảng, vật tư
//                      tăng / giảm nhiều nhất giữa 2 tháng đó; tháng đang chạy chỉ nêu dự báo.
//   "Vì sao" khác (Ratio, nhiều khung, vật tư…), ngày cụ thể, khớp loại khác -> null (LLM + context các tháng + rangeNote).
// Truy vấn các khung chạy song song trước khi dựng câu trả lời (warm); repository cache 6 giờ cho kỳ đã kết thúc.
// Chỉ T&SL, RM (OT không có Actual theo ngày). Số % / chênh lệch tính từ số đã làm tròn 1 chữ số.
// Vật tư xuất nhiều lần nhất theo khoảng: CostMonitoringItemAnswers (chạy trước lớp này).
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class CostMonitoringRangeAnswers {

	static final String RANGE_TOP_ITEM = "range_top_item";
	static final String RANGE_TOP_DETAIL = "range_top_detail";
	static final String RANGE_SUMMARY = "range_summary";
	static final String RANGE_WHY = "range_why";

	// "Vì sao" theo khoảng: tối đa số khung này (nhiều hơn -> LLM)
	private static final int WHY_MAX_FRAMES = 2;
	// Vật tư tăng / giảm giữa 2 tháng đã kết thúc: số dòng, chênh tối thiểu (K$)
	private static final int WHY_ITEMS = 3;
	private static final double WHY_ITEM_MIN_K = 0.1;

	private static final int TOP_ONE = 5;
	private static final int TOP_FEW = 3;
	// Xu hướng: % Fluct đi 1 chiều qua các tháng và chênh đầu – cuối ≥ mức này (điểm %)
	private static final double TREND_POINTS = 5.0;
	// Nhiều khung hơn mức này -> mỗi khung 1 dòng
	private static final int DETAILED_FRAMES = 2;

	private static final Pattern WHY = CostMonitoringQuestion.word("vi sao|tai sao|nguyen nhan|ly do|why|cause|reason");
	// Câu "vì sao" template được: hỏi về chi phí tăng / giảm / cao / vượt (không hỏi Ratio, khái niệm…)
	private static final Pattern WHY_SUBJECT = CostMonitoringQuestion.word(
			"tang|giam|cao|thap|vuot|nhieu|it|chi|chi phi|increase|increased|decrease|decreased|rise|rose|higher|lower|exceed|exceeded|cost|spend|spending");
	private static final Pattern DETAIL = CostMonitoringQuestion.word(
			"vat tu|ma vat tu|vat lieu|item|items|material|materials|tool|tools|dung cu|dao cu|may|machine|machines"
					+ "|cost center|category|loai vat tu|ngay nao|hom nao|which day|tuan nao|which week");
	private static final Pattern SUMMARY = CostMonitoringQuestion.word(
			"chi|chi phi|tong|bao nhieu|tinh hinh|the nao|ra sao|xu huong|tang|giam|vuot|so sanh|bien dong|actual|fluct|fc"
					+ "|tom tat|bao cao|danh gia|nhan xet|on khong|bat thuong|co gi|ket qua|trend|spend|spent|spending|cost|costs"
					+ "|summary|summarize|exceed|exceeded|overview|how|compare|result|results");

	private final CostMonitoringSnapshotLoader loader;
	private final CostTopItemsRepository repository;

	/**
	 * @return AnswerGuide có directAnswer hoặc null (LLM)
	 */
	public AnswerGuide guide(String question, Map<String, String> params, MonthRange range, String lang) {

		if (question == null || question.isBlank() || range == null) {
			return null;
		}

		String f = CostMonitoringIntentClassifier.fold(question);
		Map<String, String> safe = params != null ? params : Map.of();
		List<CostMonitoringFocus> frames = CostMonitoringLookupAnswers.frames(question, safe);
		if (frames.isEmpty()) {
			return null;
		}

		boolean why = WHY.matcher(f).find();
		// "Vì sao" chỉ nhận câu về chi phí / % Fluct của tối đa 2 khung (không hỏi vật tư / máy…) – còn lại LLM
		if (why && (frames.size() > WHY_MAX_FRAMES || DETAIL.matcher(f).find()
				|| CostMonitoringLookupAnswers.dimension(question, f) != null || !WHY_SUBJECT.matcher(f).find())) {
			return null;
		}

		String lookupKind = why ? null : CostMonitoringLookupAnswers.kind(question, f);
		String kind;
		String text;

		try {
			if (why) {
				kind = RANGE_WHY;
				text = why(frames, safe, range, lang);
			} else if (CostMonitoringLookupAnswers.TOP_ITEM.equals(lookupKind)) {
				kind = RANGE_TOP_ITEM;
				text = topItems(frames, range, CostMonitoringLookupAnswers.requestedTop(f), lang);
			} else if (CostMonitoringLookupAnswers.TOP_DETAIL.equals(lookupKind)) {
				kind = RANGE_TOP_DETAIL;
				text = topDetail(frames, range, CostMonitoringLookupAnswers.dimension(question, f),
						CostMonitoringLookupAnswers.requestedTop(f), lang);
			} else if (SUMMARY.matcher(f).find() && !DETAIL.matcher(f).find()
					&& CostMonitoringLookupAnswers.dimension(question, f) == null) {
				kind = RANGE_SUMMARY;
				text = summary(frames, safe, range, lang);
			} else {
				return null;
			}
		} catch (RuntimeException e) {
			log.warn("Assistant range answer failed error={}", e.getClass().getSimpleName());
			return null;
		}

		if (text == null || text.isBlank()) {
			return null;
		}

		String match = "range=" + range.from() + ".." + range.to() + "; kind=" + kind + "; frames="
				+ String.join(",", frames.stream().map(fr -> fr.div() + " " + fr.cost()).toList()) + "; template=yes";

		return new AnswerGuide(null, false, kind, text, suggestions(kind, frames, range, lang), match);
	}


	// =========================================================
	// TỔNG HỢP THEO TỪNG THÁNG
	// =========================================================

	// 1 tháng của 1 khung (số đã làm tròn 1 chữ số). open = tháng đang chạy (có dự báo).
	record Cell(YearMonth month, boolean open, int d, Double act, Double fluct, Double est) {

		// % so với Fluct: tháng đã kết thúc Actual / Fluct, tháng đang chạy dự báo cuối tháng / Fluct
		Double pct() {
			Double base = open ? est : act;
			return base == null || fluct == null || fluct <= 0 ? null : CmText.r1(base / fluct * 100);
		}

		// Vượt Fluct (K$): đã kết thúc Actual − Fluct, đang chạy dự báo − Fluct
		Double over() {
			return CmText.subR1(open ? est : act, fluct);
		}

		boolean exceeds() {
			Double o = over();
			return o != null && o > 0;
		}
	}

	// Bảng khung × tháng của khoảng. missing = tháng không khung nào có Actual (không có chart, hoặc chart chỉ có target –
	// vd 2026-07: test 0758 ext-243 bỏ qua tháng 07 mà không ghi "chưa có dữ liệu").
	record Table(Map<CostMonitoringFocus, List<Cell>> rows, List<YearMonth> missing, YearMonth openMonth, int openD) {
	}

	Table table(List<CostMonitoringFocus> frames, Map<String, String> params, MonthRange range) {

		// Snapshot từng tháng (song song; cache loader 3 phút). Lỗi / không có chart -> tháng thiếu dữ liệu
		YearMonth viewed = loader.month(params);
		Map<YearMonth, CostMonitoringSnapshot> snapshots = new LinkedHashMap<>();
		range.list().parallelStream().forEach(ym -> {
			try {
				CostMonitoringSnapshot s = loader.load(paramsFor(params, viewed, ym));
				if (s != null && s.response() != null && s.response().charts() != null && !s.response().charts().isEmpty()) {
					synchronized (snapshots) {
						snapshots.put(ym, s);
					}
				}
			} catch (RuntimeException e) {
				log.warn("Assistant range snapshot failed month={} error={}", ym, e.getClass().getSimpleName());
			}
		});

		List<YearMonth> months = range.list();
		if (snapshots.isEmpty()) {
			return null;
		}

		// Ô dữ liệu theo khung × tháng
		Map<CostMonitoringFocus, List<Cell>> table = new LinkedHashMap<>();
		YearMonth openMonth = null;
		int openD = 0;

		for (CostMonitoringFocus fr : frames) {
			List<Cell> cells = new ArrayList<>();
			for (YearMonth ym : months) {
				CostMonitoringSnapshot s = snapshots.get(ym);
				CostChartDto chart = s == null ? null : s.find(fr.div(), fr.cost());
				if (chart == null) {
					continue;
				}
				CostMonitoringAssessment a = CostMonitoringAssessment.of(s, chart);
				if (a.act() == null || a.fluct() == null) {
					continue;
				}
				boolean open = !s.monthClosed();
				cells.add(new Cell(ym, open, s.d(), CmText.r1(a.act()), CmText.r1(a.fluct()),
						open ? CmText.r1(a.estimateEom()) : null));
				if (open) {
					openMonth = ym;
					openD = s.d();
				}
			}
			if (!cells.isEmpty()) {
				table.put(fr, cells);
			}
		}

		if (table.isEmpty()) {
			return null;
		}

		java.util.Set<YearMonth> withData = new java.util.HashSet<>();
		table.values().forEach(cells -> cells.forEach(c -> withData.add(c.month())));
		List<YearMonth> missing = months.stream().filter(ym -> !withData.contains(ym)).toList();

		return new Table(table, missing, openMonth, openD);
	}

	String summary(List<CostMonitoringFocus> frames, Map<String, String> params, MonthRange range, String lang) {

		Table data = table(frames, params, range);
		if (data == null) {
			return null;
		}

		Map<CostMonitoringFocus, List<Cell>> table = data.rows();
		boolean detailed = table.size() <= DETAILED_FRAMES;
		StringBuilder sb = new StringBuilder();

		sb.append("**").append(t(lang, "Chi phí theo tháng – khoảng ", "Monthly cost – range ")).append(range.label())
				.append(" (").append(range.months()).append(t(lang, " tháng)**", " months)**"));
		if (data.openMonth() != null) {
			sb.append(t(lang, " – tháng " + data.openMonth() + " đến ngày " + data.openD() + ", kèm dự báo cuối tháng",
					" – " + data.openMonth() + " up to day " + data.openD() + ", with month-end forecast"));
		}
		sb.append(":\n");

		if (!detailed) {
			sb.append('\n');
		}

		for (Map.Entry<CostMonitoringFocus, List<Cell>> e : table.entrySet()) {
			String name = e.getKey().div() + " " + e.getKey().cost();
			List<Cell> cells = e.getValue();
			if (detailed) {
				appendDetailed(sb, name, cells, range, data.missing(), lang);
			} else {
				appendCompact(sb, name, cells, range, data.missing(), lang);
			}
		}

		// Nhận định
		List<String> insight = insight(table, lang);
		sb.append('\n').append(t(lang, "→ **Nhận định:** ", "→ **Assessment:** ")).append(String.join(" ", insight)).append('\n');

		sb.append('\n').append(footer(data, !detailed, lang));

		return sb.toString().trim();
	}

	// "_% = Actual / Fluct …; Chưa có dữ liệu (không có Actual trên màn hình): 2026-07. Target …_"
	private static String footer(Table data, boolean compact, String lang) {

		StringBuilder sb = new StringBuilder("_");
		if (compact) {
			sb.append(t(lang, "% = Actual / Fluct của từng tháng", "% = Actual / Fluct per month"));
			// Chỉ nhắc tháng đang chạy khi khoảng có tháng đó (test 0758 ext-239: khoảng 08–10 vẫn ghi "tháng đang chạy")
			if (data.openMonth() != null) {
				sb.append(t(lang, "; tháng đang chạy: dự báo cuối tháng / Fluct (dự báo chính như màn hình)",
						"; running month: month-end forecast / Fluct (main forecast as on screen)"));
			}
			sb.append(". ");
		}
		if (!data.missing().isEmpty()) {
			sb.append(t(lang, "Chưa có dữ liệu (không có Actual trên màn hình): " + join(data.missing()) + ". ",
					"No data (no Actual on screen): " + join(data.missing()) + ". "));
		}
		sb.append(t(lang, "Target (Fluct) tính theo từng tháng; chỉ T&SL, RM (OT không có Actual theo ngày).",
				"Targets (Fluct) are per month; T&SL and RM only (OT has no daily Actual).")).append('_');
		return sb.toString();
	}

	// **PRESS T&SL**
	// - 2026-07: chưa có dữ liệu
	// - 2026-09: Actual 72.1 K$ / Fluct 80.0 K$ (90.1%) – trong Fluct (-7.9 K$)
	// - 2026-11 (đến ngày 10): Actual 25.0 K$ / Fluct 77.0 K$; dự báo cuối tháng 78.0 K$ (101.3% Fluct) – dự báo vượt +1.0 K$
	// - Cộng 2 tháng đã kết thúc: Actual 152.4 K$ / Fluct 155.0 K$ (98.3%, -2.6 K$)
	private static void appendDetailed(StringBuilder sb, String name, List<Cell> cells, MonthRange range,
									   List<YearMonth> missing, String lang) {

		sb.append("\n**").append(name).append("**\n");

		Map<YearMonth, Cell> byMonth = new LinkedHashMap<>();
		cells.forEach(c -> byMonth.put(c.month(), c));

		for (YearMonth ym : range.list()) {
			Cell c = byMonth.get(ym);
			if (c == null) {
				if (missing.contains(ym)) {
					sb.append("- ").append(ym).append(": ").append(t(lang, "chưa có dữ liệu", "no data")).append('\n');
				}
				continue;
			}
			sb.append("- ").append(c.month());
			if (c.open()) {
				sb.append(t(lang, " (đến ngày " + c.d() + ")", " (to day " + c.d() + ")"));
			}
			sb.append(": Actual ").append(CmText.k1(c.act())).append(" / Fluct ").append(CmText.k1(c.fluct()));
			if (c.open()) {
				if (c.est() == null) {
					sb.append(t(lang, "; chưa có dự báo", "; no forecast yet"));
				} else {
					sb.append(t(lang, "; dự báo cuối tháng ", "; month-end forecast ")).append(CmText.k1(c.est()))
							.append(" (").append(CmText.pct1(c.pct())).append(" Fluct) – ")
							.append(c.exceeds() ? t(lang, "dự báo vượt ", "forecast above ") + CmText.signedK(c.over())
									: t(lang, "dự báo trong Fluct (", "forecast within Fluct (") + CmText.signedK(c.over()) + ")");
				}
			} else {
				sb.append(" (").append(CmText.pct1(c.pct())).append(") – ")
						.append(c.exceeds() ? t(lang, "vượt Fluct ", "above Fluct ") + CmText.signedK(c.over())
								: t(lang, "trong Fluct (", "within Fluct (") + CmText.signedK(c.over()) + ")");
			}
			sb.append('\n');
		}

		List<Cell> closed = cells.stream().filter(c -> !c.open()).toList();
		if (closed.size() >= 2) {
			double act = CmText.r1(closed.stream().mapToDouble(Cell::act).sum());
			double fluct = CmText.r1(closed.stream().mapToDouble(Cell::fluct).sum());
			sb.append(t(lang, "- Cộng " + closed.size() + " tháng đã kết thúc: Actual ",
							"- Total of " + closed.size() + " closed months: Actual ")).append(CmText.k1(act))
					.append(" / Fluct ").append(CmText.k1(fluct));
			if (fluct > 0) {
				sb.append(" (").append(CmText.pct1(CmText.r1(act / fluct * 100))).append(", ")
						.append(CmText.signedK(CmText.subR1(act, fluct))).append(')');
			}
			sb.append('\n');
		}
	}

	// - **PRESS T&SL**: 07 chưa có số · 09 90.1% · 10 107.1% · 11 dự báo 101.3% – vượt 2/3 tháng
	private static void appendCompact(StringBuilder sb, String name, List<Cell> cells, MonthRange range,
									  List<YearMonth> missing, String lang) {

		Map<YearMonth, Cell> byMonth = new LinkedHashMap<>();
		cells.forEach(c -> byMonth.put(c.month(), c));

		List<String> parts = new ArrayList<>();
		for (YearMonth ym : range.list()) {
			Cell c = byMonth.get(ym);
			if (c == null) {
				if (missing.contains(ym)) {
					parts.add(monthLabel(ym, range) + t(lang, " chưa có số", " no data"));
				}
				continue;
			}
			String pct = c.pct() == null ? CmText.noData(lang) : CmText.pct1(c.pct());
			parts.add(monthLabel(c.month(), range) + " " + (c.open() ? t(lang, "dự báo ", "forecast ") : "") + pct
					+ (c.exceeds() ? " ⚠" : ""));
		}
		long over = cells.stream().filter(Cell::exceeds).count();

		sb.append("- **").append(name).append("**: ").append(String.join(" · ", parts))
				.append(" – ").append(over == 0 ? t(lang, "không tháng nào vượt Fluct", "no month above Fluct")
						: t(lang, "vượt Fluct " + over + "/" + cells.size() + " tháng", "above Fluct in " + over + "/" + cells.size() + " months"))
				.append('\n');
	}

	// Nhận định: vượt lặp lại (≥ 2 tháng, kể cả dự báo tháng đang chạy), xu hướng % Fluct, khung nặng nhất
	private static List<String> insight(Map<CostMonitoringFocus, List<Cell>> table, String lang) {

		List<String> repeat = new ArrayList<>();
		List<String> rising = new ArrayList<>();
		List<String> falling = new ArrayList<>();
		String worst = null;
		double worstPct = -1;

		for (Map.Entry<CostMonitoringFocus, List<Cell>> e : table.entrySet()) {

			String name = e.getKey().div() + " " + e.getKey().cost();
			List<Cell> cells = e.getValue();

			long over = cells.stream().filter(Cell::exceeds).count();
			if (over >= 2) {
				repeat.add(name + " (" + over + "/" + cells.size() + t(lang, " tháng)", " months)"));
			}

			List<Double> pcts = cells.stream().map(Cell::pct).filter(java.util.Objects::nonNull).toList();
			if (pcts.size() >= 2) {
				boolean up = true;
				boolean down = true;
				for (int i = 1; i < pcts.size(); i++) {
					up &= pcts.get(i) >= pcts.get(i - 1);
					down &= pcts.get(i) <= pcts.get(i - 1);
				}
				double change = CmText.r1(pcts.get(pcts.size() - 1) - pcts.get(0));
				String delta = String.format(Locale.ROOT, "%+.1f", change) + t(lang, " điểm %", " pts");
				if (up && change >= TREND_POINTS) {
					rising.add(name + " (" + CmText.pct1(pcts.get(0)) + " → " + CmText.pct1(pcts.get(pcts.size() - 1)) + ", " + delta + ")");
				} else if (down && -change >= TREND_POINTS) {
					falling.add(name + " (" + CmText.pct1(pcts.get(0)) + " → " + CmText.pct1(pcts.get(pcts.size() - 1)) + ", " + delta + ")");
				}
			}

			// Khung nặng nhất: Σ Actual / Σ Fluct các tháng đã kết thúc (không có thì tháng đang chạy theo dự báo)
			List<Cell> closed = cells.stream().filter(c -> !c.open()).toList();
			Double pct;
			if (!closed.isEmpty()) {
				double act = CmText.r1(closed.stream().mapToDouble(Cell::act).sum());
				double fluct = CmText.r1(closed.stream().mapToDouble(Cell::fluct).sum());
				pct = fluct > 0 ? CmText.r1(act / fluct * 100) : null;
			} else {
				pct = cells.get(cells.size() - 1).pct();
			}
			if (pct != null && pct > worstPct) {
				worstPct = pct;
				worst = name;
			}
		}

		List<String> out = new ArrayList<>();

		if (!repeat.isEmpty()) {
			out.add(t(lang, "**Vượt Fluct lặp lại**: " + String.join(", ", repeat)
							+ " – không phải biến động 1 lần, nên rà định mức sử dụng / target.",
					"**Repeatedly above Fluct**: " + String.join(", ", repeat)
							+ " – not a one-off, review usage norms / target."));
		}
		if (!rising.isEmpty()) {
			out.add(t(lang, "**% Fluct tăng dần**: " + String.join(", ", rising) + " – nên theo dõi sớm.",
					"**% of Fluct rising**: " + String.join(", ", rising) + " – watch early."));
		}
		if (!falling.isEmpty()) {
			out.add(t(lang, "Giảm dần: " + String.join(", ", falling) + ".",
					"Falling: " + String.join(", ", falling) + "."));
		}
		if (out.isEmpty()) {
			out.add(t(lang, "Không khung nào vượt Fluct lặp lại hay tăng đều trong khoảng – chi phí tương đối ổn định.",
					"No frame is repeatedly above Fluct or steadily rising in the range – costs are fairly stable."));
		}
		if (worst != null && table.size() > 1) {
			// Ghi rõ cơ sở (test 0758 ext-239: "120.4%" không rõ là cộng 3 tháng)
			out.add(t(lang, "Khung tỷ lệ cao nhất (Σ Actual / Σ Fluct các tháng đã kết thúc): **" + worst + "** ("
							+ CmText.pct1(worstPct) + " Fluct).",
					"Highest ratio (Σ Actual / Σ Fluct of closed months): **" + worst + "** (" + CmText.pct1(worstPct) + " of Fluct)."));
		}

		return out;
	}


	// =========================================================
	// VÌ SAO TĂNG / GIẢM TRONG KHOẢNG (range_why)
	//
	// Test 0758 ext-250 "Vì sao từ tháng 9 đến tháng 11 MOLD RM tăng?": LLM 141 s, số mâu thuẫn ("89.4 K$ – vẫn dưới
	// Fluct" khi Fluct 83.1). Code tính: bảng từng tháng (như range_summary) + mỗi khung so tháng đã kết thúc đầu và cuối
	// khoảng: Actual (chi) đổi bao nhiêu, Fluct (target) đổi bao nhiêu -> yếu tố chính; vật tư tăng / giảm nhiều nhất giữa
	// 2 tháng đó (dòng xuất kho, chi phí trực tiếp). Tháng đang chạy chỉ nêu dự báo (Actual chưa đủ tháng, không so).
	// =========================================================

	String why(List<CostMonitoringFocus> frames, Map<String, String> params, MonthRange range, String lang) {

		Table data = table(frames, params, range);
		if (data == null) {
			return null;
		}

		// Nạp trước (song song) tổng theo vật tư của 2 tháng so sánh mỗi khung (tháng đã kết thúc -> cache 6 giờ)
		warm(new ArrayList<>(data.rows().keySet()), fr -> {
			List<Cell> closed = data.rows().get(fr).stream().filter(c -> !c.open()).toList();
			if (closed.size() >= 2) {
				sumByItem(fr, closed.get(0).month());
				sumByItem(fr, closed.get(closed.size() - 1).month());
			}
		});

		StringBuilder sb = new StringBuilder();
		sb.append("**").append(t(lang, "Phân tích theo tháng – khoảng ", "Month-by-month analysis – range ")).append(range.label())
				.append(" (").append(range.months()).append(t(lang, " tháng)**", " months)**"));
		if (data.openMonth() != null) {
			sb.append(t(lang, " – tháng " + data.openMonth() + " đến ngày " + data.openD(),
					" – " + data.openMonth() + " up to day " + data.openD()));
		}
		sb.append(":\n");

		List<String> drivers = new ArrayList<>();
		for (Map.Entry<CostMonitoringFocus, List<Cell>> e : data.rows().entrySet()) {
			String name = e.getKey().div() + " " + e.getKey().cost();
			appendDetailed(sb, name, e.getValue(), range, data.missing(), lang);
			drivers.addAll(drivers(e.getKey(), name, e.getValue(), lang));
		}

		sb.append('\n').append(t(lang, "→ **Yếu tố (hệ thống tính):**", "→ **Drivers (computed):**")).append('\n');
		drivers.forEach(d -> sb.append("- ").append(d).append('\n'));

		sb.append('\n').append(t(lang, "→ **Nhận định:** ", "→ **Assessment:** "))
				.append(String.join(" ", insight(data.rows(), lang))).append('\n');

		sb.append('\n').append(footer(data, false, lang).replace("._", ". " + t(lang,
				"Vật tư: chi phí trực tiếp từ dòng xuất kho, chưa gồm phân bổ F2/HT2/HT-G; không có dữ liệu sản lượng nên không kết luận nguyên nhân sản xuất.",
				"Items: direct cost from issue lines, excludes F2/HT2/HT-G allocation; no production data, so production causes are not concluded.") + "_"));

		return sb.toString().trim();
	}

	// Yếu tố của 1 khung: so tháng đã kết thúc đầu – cuối khoảng; tháng đang chạy chỉ nêu dự báo
	private List<String> drivers(CostMonitoringFocus fr, String name, List<Cell> cells, String lang) {

		List<String> out = new ArrayList<>();
		List<Cell> closed = cells.stream().filter(c -> !c.open()).toList();
		Cell open = cells.stream().filter(Cell::open).findFirst().orElse(null);

		if (closed.size() >= 2) {
			Cell a = closed.get(0);
			Cell b = closed.get(closed.size() - 1);
			Double dAct = CmText.subR1(b.act(), a.act());
			Double dFluct = CmText.subR1(b.fluct(), a.fluct());
			out.add("**" + name + "** " + a.month() + " → " + b.month() + ": Actual " + CmText.k1(a.act()) + " → " + CmText.k1(b.act())
					+ " (" + CmText.signedK(dAct) + "); Fluct " + CmText.k1(a.fluct()) + " → " + CmText.k1(b.fluct())
					+ " (" + CmText.signedK(dFluct)
					+ (dFluct == null || dFluct == 0 ? "" : dFluct < 0 ? t(lang, ", target bị hạ", ", target cut") : t(lang, ", target được nâng", ", target raised"))
					+ "); % Fluct " + CmText.pct1(a.pct()) + " → " + CmText.pct1(b.pct()) + " → " + cause(dAct, dFluct, lang));
			String items = itemDrivers(fr, a.month(), b.month(), lang);
			if (items != null) {
				out.add(items);
			}
		} else if (closed.size() == 1 && open != null) {
			Cell a = closed.get(0);
			out.add("**" + name + "**: " + a.month() + " " + CmText.pct1(a.pct()) + " Fluct → " + open.month()
					+ t(lang, " dự báo ", " forecast ") + CmText.pct1(open.pct())
					+ t(lang, " (chưa đủ 2 tháng đã kết thúc để tách phần do chi và do target)",
					" (fewer than 2 closed months, spending and target effects not separated)"));
		} else {
			out.add("**" + name + "**: " + t(lang, "chưa đủ 2 tháng đã kết thúc trong khoảng để so sánh.",
					"fewer than 2 closed months in the range to compare."));
		}

		if (open != null && open.est() != null) {
			out.add("**" + name + "** " + open.month() + t(lang, " (đến ngày " + open.d() + "): dự báo cuối tháng ",
					" (to day " + open.d() + "): month-end forecast ") + CmText.k1(open.est()) + " (" + CmText.pct1(open.pct())
					+ " Fluct) – " + (open.exceeds()
					? t(lang, "dự báo vượt ", "forecast above ") + CmText.signedK(open.over())
					: t(lang, "dự báo trong Fluct (", "forecast within Fluct (") + CmText.signedK(open.over()) + ")")
					+ t(lang, "; Actual chưa đủ tháng nên không so trực tiếp với các tháng trước.",
					"; Actual is month-to-date, not compared directly with earlier months."));
		}

		return out;
	}

	// Chi (Actual) và target (Fluct) đổi giữa 2 tháng -> yếu tố chính của thay đổi % Fluct
	private static String cause(Double dAct, Double dFluct, String lang) {

		if (dAct == null || dFluct == null) {
			return t(lang, "thiếu số để tách yếu tố", "missing numbers to separate drivers");
		}
		if (dAct > 0 && dFluct < 0) {
			return t(lang, "**chi tăng và target bị hạ** cùng đẩy % Fluct lên", "**spending up and target cut** both push % of Fluct up");
		}
		if (dAct > 0) {
			return t(lang, "chủ yếu do **chi tăng**" + (dFluct > 0 ? " (target được nâng nhưng không bù đủ)" : ""),
					"mainly **higher spending**" + (dFluct > 0 ? " (target raised but not enough)" : ""));
		}
		if (dFluct < 0) {
			return t(lang, "chi không tăng; % Fluct đổi chủ yếu do **target bị hạ**", "spending did not rise; % of Fluct moved mainly due to a **target cut**");
		}
		return t(lang, "chi không tăng và target không bị hạ", "spending did not rise and the target was not cut");
	}

	// "Vật tư tăng nhiều nhất 2026-10 so với 2026-09: A +1.20 K$ (0.30 → 1.50); … Giảm nhiều nhất: …"
	private String itemDrivers(CostMonitoringFocus fr, YearMonth m1, YearMonth m2, String lang) {

		Map<String, Object[]> map = new LinkedHashMap<>();
		for (com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto i : sumByItem(fr, m1)) {
			Object[] v = map.computeIfAbsent(itemKey(i), k -> new Object[]{CmText.itemLabel(i.code(), i.name(), lang), 0.0, 0.0});
			v[1] = (double) v[1] + (i.amountK() != null ? i.amountK() : 0);
		}
		for (com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto i : sumByItem(fr, m2)) {
			Object[] v = map.computeIfAbsent(itemKey(i), k -> new Object[]{CmText.itemLabel(i.code(), i.name(), lang), 0.0, 0.0});
			v[2] = (double) v[2] + (i.amountK() != null ? i.amountK() : 0);
		}
		if (map.isEmpty()) {
			return null;
		}

		record Change(String label, double before, double after, double diff) {
		}
		List<Change> changes = map.values().stream()
				.map(v -> {
					double before = CmText.r2((double) v[1]);
					double after = CmText.r2((double) v[2]);
					return new Change((String) v[0], before, after, CmText.r2(after - before));
				})
				.toList();

		List<Change> up = changes.stream().filter(c -> c.diff() >= WHY_ITEM_MIN_K)
				.sorted(java.util.Comparator.comparingDouble(Change::diff).reversed()).limit(WHY_ITEMS).toList();
		List<Change> down = changes.stream().filter(c -> c.diff() <= -WHY_ITEM_MIN_K)
				.sorted(java.util.Comparator.comparingDouble(Change::diff)).limit(WHY_ITEMS).toList();
		if (up.isEmpty() && down.isEmpty()) {
			return null;
		}

		java.util.function.Function<Change, String> fmt = c -> c.label() + " " + String.format(Locale.ROOT, "%+.2f K$", c.diff())
				+ " (" + String.format(Locale.ROOT, "%.2f → %.2f", c.before(), c.after()) + ")";
		StringBuilder sb = new StringBuilder("**" + fr.div() + " " + fr.cost() + "** – ");
		if (!up.isEmpty()) {
			sb.append(t(lang, "vật tư tăng nhiều nhất " + m2 + " so với " + m1 + ": ", "items up most " + m2 + " vs " + m1 + ": "))
					.append(String.join("; ", up.stream().map(fmt).toList()));
		}
		if (!down.isEmpty()) {
			sb.append(up.isEmpty() ? "" : ". ").append(t(lang, "Giảm nhiều nhất: ", "Down most: "))
					.append(String.join("; ", down.stream().map(fmt).toList()));
		}
		return sb.append('.').toString();
	}

	private static String itemKey(com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto i) {
		return i.code() != null && !i.code().isBlank() ? i.code().trim() : String.valueOf(i.name());
	}

	// Tổng theo vật tư của 1 tháng trọn vẹn (chỉ gọi cho tháng đã kết thúc)
	private List<com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto> sumByItem(CostMonitoringFocus fr, YearMonth month) {
		return repository.sumByItem(fr.cost(), month.atDay(1), month.atEndOfMonth(), CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div()));
	}

	// Chạy trước (song song) truy vấn của từng khung để nạp cache repository; phần dựng câu trả lời sau đó đọc cache.
	// Test 0758: 6 khung × 2 truy vấn tuần tự trên khoảng 3 tháng mất 30–42 s (ext-237), 11 tháng 262–277 s (ext-244).
	// Lỗi -> bỏ qua (truy vấn lại tuần tự khi dựng).
	private static void warm(List<CostMonitoringFocus> frames, java.util.function.Consumer<CostMonitoringFocus> task) {

		if (frames.size() < 2) {
			return;
		}

		try {
			frames.parallelStream().forEach(task);
		} catch (RuntimeException e) {
			log.warn("Assistant range warm-up failed error={}", e.getClass().getSimpleName());
		}
	}


	// =========================================================
	// TOP VẬT TƯ / MÁY / COST CENTER / CATEGORY TRÊN CẢ KHOẢNG
	// =========================================================

	String topItems(List<CostMonitoringFocus> frames, MonthRange range, int topN, String lang) {

		boolean single = frames.size() == 1;
		int limit = single ? (topN > 0 ? topN : TOP_ONE) : (frames.size() <= 2 ? TOP_FEW : 1);
		StringBuilder body = new StringBuilder();
		int blocks = 0;
		LocalDate from = range.from().atDay(1);

		// findTopItems nạp kèm tổng khung (TotalK) -> findTotal bên dưới đọc cache
		warm(frames, fr -> {
			LocalDate to = to(range, fr.cost());
			if (!to.isBefore(from)) {
				repository.findTopItems(fr.cost(), from, to, CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div()), limit);
			}
		});

		for (CostMonitoringFocus fr : frames) {

			LocalDate to = to(range, fr.cost());
			if (to.isBefore(from)) {
				continue;
			}
			List<String> centers = CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div());
			List<CostTopItemDto> items = repository.findTopItems(fr.cost(), from, to, centers, limit).stream()
					.filter(i -> i.amountK() != null && i.amountK() > 0).toList();
			if (items.isEmpty()) {
				continue;
			}
			Double total = repository.findTotal(fr.cost(), from, to, centers);
			blocks++;

			if (limit == 1) {
				CostTopItemDto i = items.get(0);
				body.append("- ").append(fr.div()).append(' ').append(fr.cost()).append(": **")
						.append(CmText.itemLabel(i.code(), i.name(), lang)).append("** – ").append(k2(i.amountK()))
						.append(share(i.amountK(), total, lang)).append('\n');
				continue;
			}

			body.append("\n**").append(fr.div()).append(' ').append(fr.cost()).append("**");
			if (total != null) {
				body.append(t(lang, " (tổng chi ", " (total ")).append(CmText.k1(total)).append(')');
			}
			body.append(":\n");
			int n = 0;
			for (CostTopItemDto i : items) {
				body.append(++n).append(". **").append(CmText.itemLabel(i.code(), i.name(), lang)).append("**");
				String category = CmText.clean(i.category());
				if (!category.isEmpty()) {
					body.append(" – ").append(category);
				}
				body.append(": ").append(k2(i.amountK())).append(share(i.amountK(), total, lang));
				if (i.qty() != null && i.qty() > 0) {
					body.append("; SL ").append(qty(i.qty())).append(i.qtyUnit() == null || i.qtyUnit().isBlank() ? "" : " " + i.qtyUnit().trim());
				}
				body.append('\n');
			}
		}

		if (blocks == 0) {
			return null;
		}

		return header(t(lang, "Vật tư chi nhiều nhất (theo tổng chi K$)", "Top items by total spending (K$)"), range, lang)
				+ body.toString().replaceFirst("^\n", "")
				+ "\n_" + t(lang, "Mỗi khung xếp riêng (không cộng T&SL với RM). Chỉ chi phí trực tiếp, chưa gồm phân bổ F2/HT2/HT-G. "
						+ "Theo số lần xuất: hỏi \"Vật tư nào xuất nhiều lần nhất\".",
				"Each frame ranked separately (T&SL and RM are not added). Direct cost only, excludes F2/HT2/HT-G allocation. "
						+ "By number of issues: ask \"Which items are issued most often\".") + "_";
	}

	String topDetail(List<CostMonitoringFocus> frames, MonthRange range, Dimension dim, int topN, String lang) {

		if (dim == null) {
			return null;
		}

		List<CostMonitoringFocus> usable = dim == Dimension.MACHINE
				? frames.stream().filter(fr -> "T&SL".equals(fr.cost())).toList() : frames;
		if (usable.isEmpty()) {
			return null;
		}

		int limit = usable.size() == 1 ? (topN > 0 ? topN : TOP_ONE) : (usable.size() <= 2 ? TOP_FEW : 1);
		LocalDate from = range.from().atDay(1);
		StringBuilder body = new StringBuilder();
		int blocks = 0;

		// Cost center / Category nạp kèm tổng khung; máy thì tổng khung là truy vấn riêng (chỉ dòng có Machine ở top)
		warm(usable, fr -> {
			LocalDate to = to(range, fr.cost());
			if (to.isBefore(from)) {
				return;
			}
			List<String> centers = CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div());
			switch (dim) {
				case MACHINE -> {
					repository.findTopMachines(fr.cost(), from, to, centers, limit);
					repository.findTotal(fr.cost(), from, to, centers);
				}
				case COST_CENTER -> repository.findTopCostCenters(fr.cost(), from, to, centers, limit);
				case CATEGORY -> repository.sumByCategory(fr.cost(), from, to, centers);
			}
		});

		for (CostMonitoringFocus fr : usable) {

			LocalDate to = to(range, fr.cost());
			if (to.isBefore(from)) {
				continue;
			}
			List<String> centers = CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div());
			List<String[]> rows = new ArrayList<>();
			List<Double> amounts = new ArrayList<>();

			switch (dim) {
				case MACHINE -> repository.findTopMachines(fr.cost(), from, to, centers, limit).forEach(m -> {
					rows.add(new String[]{CmText.clean(m.machine())});
					amounts.add(m.amountK());
				});
				case COST_CENTER -> repository.findTopCostCenters(fr.cost(), from, to, centers, limit).forEach(c -> {
					rows.add(new String[]{CmText.clean(c.name())
							+ (c.code() == null || c.code().isBlank() ? "" : " (" + CmText.clean(c.code()) + ")")});
					amounts.add(c.amountK());
				});
				case CATEGORY -> repository.sumByCategory(fr.cost(), from, to, centers).entrySet().stream()
						.sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(limit)
						.forEach(en -> {
							rows.add(new String[]{CmText.clean(en.getKey())});
							amounts.add(en.getValue());
						});
			}

			List<Integer> valid = new ArrayList<>();
			for (int i = 0; i < rows.size(); i++) {
				if (amounts.get(i) != null && amounts.get(i) > 0) {
					valid.add(i);
				}
			}
			if (valid.isEmpty()) {
				continue;
			}
			Double total = repository.findTotal(fr.cost(), from, to, centers);
			blocks++;

			if (limit == 1) {
				int i = valid.get(0);
				body.append("- ").append(fr.div()).append(' ').append(fr.cost()).append(": **").append(rows.get(i)[0])
						.append("** – ").append(k2(amounts.get(i))).append(share(amounts.get(i), total, lang)).append('\n');
				continue;
			}

			body.append("\n**").append(fr.div()).append(' ').append(fr.cost()).append("**");
			if (total != null) {
				body.append(t(lang, " (tổng chi ", " (total ")).append(CmText.k1(total)).append(')');
			}
			body.append(":\n");
			int n = 0;
			for (int i : valid) {
				body.append(++n).append(". **").append(rows.get(i)[0]).append("**: ").append(k2(amounts.get(i)))
						.append(share(amounts.get(i), total, lang)).append('\n');
			}
		}

		if (blocks == 0) {
			return null;
		}

		String what = switch (dim) {
			case MACHINE -> t(lang, "Máy chi nhiều nhất", "Machines with the highest spending");
			case COST_CENTER -> t(lang, "Cost center chi nhiều nhất", "Cost centers with the highest spending");
			case CATEGORY -> t(lang, "Category chi nhiều nhất", "Categories with the highest spending");
		};

		return header(what, range, lang)
				+ body.toString().replaceFirst("^\n", "")
				+ "\n_" + t(lang, "Mỗi khung xếp riêng (không cộng T&SL với RM). "
						+ (dim == Dimension.MACHINE ? "Chỉ dòng có ghi Machine (RM không ghi theo máy). " : "")
						+ "Chỉ chi phí trực tiếp, chưa gồm phân bổ F2/HT2/HT-G.",
				"Each frame ranked separately (T&SL and RM are not added). "
						+ (dim == Dimension.MACHINE ? "Only lines with a Machine (RM has no machine data). " : "")
						+ "Direct cost only, excludes F2/HT2/HT-G allocation.") + "_";
	}

	// "**Vật tư chi nhiều nhất – khoảng 2026-09 → 2026-11 (3 tháng, 01/09–10/11):**\n"
	private String header(String what, MonthRange range, String lang) {
		LocalDate to = to(range, "T&SL");
		return "**" + what + t(lang, " – khoảng ", " – range ") + range.label() + " (" + range.months()
				+ t(lang, " tháng, ", " months, ") + CostMonitoringSnapshot.ddmm(range.from().atDay(1)) + "/" + range.from().getYear()
				+ "–" + CostMonitoringSnapshot.ddmm(to) + "/" + to.getYear() + ")**"
				+ (to.isBefore(range.to().atEndOfMonth())
				? t(lang, " – tháng " + range.to() + " tính đến mốc actual (RM chậm 1 ngày)",
				" – " + range.to() + " up to the actual date (RM lags 1 day)") : "")
				+ ":\n";
	}


	// =========================================================
	// TIỆN ÍCH
	// =========================================================

	// Mốc cuối dữ liệu dòng xuất: min(cuối tháng cuối khoảng, mốc actual – T&SL hôm qua, RM hôm kia)
	private LocalDate to(MonthRange range, String cost) {
		LocalDate cutoff = loader.today().minusDays("RM".equals(cost) ? 2 : 1);
		LocalDate end = range.to().atEndOfMonth();
		return cutoff.isBefore(end) ? cutoff : end;
	}

	// Params của 1 tháng (giống CostMonitoringModule.paramsForMonth): tháng đang xem giữ nguyên params
	private static Map<String, String> paramsFor(Map<String, String> params, YearMonth viewed, YearMonth ym) {
		if (ym.equals(viewed)) {
			return params;
		}
		Map<String, String> result = new HashMap<>(params);
		result.put("month", ym.toString());
		result.remove("simuWeek");
		result.remove("plTgtMode");
		return result;
	}

	// " (12.3% tổng chi khung)"; thiếu số -> trống
	private static String share(Double amountK, Double total, String lang) {
		if (amountK == null || total == null || total <= 0) {
			return "";
		}
		return " (" + CmText.pct1(CmText.r1(CmText.r2(amountK) / CmText.r2(total) * 100)) + t(lang, " tổng chi khung)", " of frame total)");
	}

	private static String k2(Double value) {
		return value == null ? "" : String.format(Locale.ROOT, "%.2f K$", value);
	}

	private static String qty(Double value) {
		return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
	}

	// "09" (cùng năm với tháng cuối khoảng) / "2025-12"
	private static String monthLabel(YearMonth ym, MonthRange range) {
		return ym.getYear() == range.to().getYear() ? String.format(Locale.ROOT, "%02d", ym.getMonthValue()) : ym.toString();
	}

	private static String join(List<YearMonth> months) {
		return String.join(", ", months.stream().map(YearMonth::toString).toList());
	}

	// Gợi ý: câu tiếp theo cùng khoảng ("từ 2026-09 đến 2026-11" – lõi dò được dạng yyyy-MM … yyyy-MM)
	private static List<String> suggestions(String kind, List<CostMonitoringFocus> frames, MonthRange range, String lang) {

		String span = t(lang, " từ " + range.from() + " đến " + range.to(), " from " + range.from() + " to " + range.to());
		String name = frames.size() == 1 ? " " + frames.get(0).div() + " " + frames.get(0).cost() : "";
		List<String> out = new ArrayList<>();

		if (!RANGE_TOP_ITEM.equals(kind)) {
			out.add(t(lang, "Vật tư nào chi nhiều nhất" + (name.isEmpty() ? "" : " ở" + name) + span + "?",
					"Which items cost the most" + (name.isEmpty() ? "" : " in" + name) + span + "?"));
		}
		if (!RANGE_SUMMARY.equals(kind)) {
			out.add(t(lang, "Chi phí" + name + span + " thế nào?", "How was the cost" + name + span + "?"));
		}
		out.add(t(lang, "Vật tư nào xuất nhiều lần nhất" + span + "?", "Which items were issued most often" + span + "?"));

		return out.stream().limit(3).toList();
	}
}
