package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.tool.AssistantTool;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostLineDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopItemDto;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostTopItemsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// TOOL explain_variance(div, cost)
//
// Mọi phép tính làm bằng code, model chỉ diễn giải. Kết quả chỉ có nhãn dễ đọc theo lang
// (CmText), không gửi mã nội bộ. 2 chế độ (CostMonitoringAssessment.mode):
//
//   IN_PROGRESS (Act ≤ Fluct):
//     "Theo tiến độ nên chi" = Fluct × D / N
//     "Chi vượt tiến độ" (ExcessVsPace) = Act − Fluct × D / N
//       (= phần dự báo vượt khi Forecast tính theo Fluct)
//     Kết luận chính: EARLY_OVERSPEND (> 0) | ON_TRACK (≤ 0). Không xuất TargetCut / OverFC.
//
//   OVER (Act > Fluct):
//     Gap = Act − Fluct = TargetCut (FC − Fluct) + OverFC (Act − FC)
//     Kết luận chính: TARGET_CUT | OVER_SPEND | BOTH (cả 2 phần ≥ 30% Gap) | NONE (Gap ≈ 0)
//
//   CLOSED_WITHIN (tháng đã kết thúc, Act ≤ Fluct): Kết quả tháng, chênh so với Fluct / FC / PL.
//   Tháng đã kết thúc (OVER hoặc CLOSED_WITHIN): không xuất tiến độ, dự báo, rủi ro, kịch bản;
//     "Kết quả tháng" thay cho Rủi ro; khoản lớn bất thường so phần còn lại với Fluct cả tháng.
//   OVER: thêm "Tỷ trọng do target bị hạ" = (FC − Fluct) / Phần vượt Fluct × 100
//     và "Tỷ trọng do chi vượt FC" = (Act − FC) / Phần vượt Fluct × 100.
//   Thiếu Act / Fluct: NO_DATA.
//   OVER: thêm "Badge (% so với Fluct)" và "% so với FC" = Act / FC × 100 (giá trị chưa làm tròn, như badge).
//   Mọi giá trị chênh lệch có dấu (+/-) kèm nhãn, vd "Chi vượt tiến độ=+18.6 K$ (chi nhiều hơn tiến độ)".
//   Mọi phép cộng / trừ hiển thị và tỷ trọng % tính từ số ĐÃ làm tròn như khi hiển thị
//   (K$ 1 chữ số: FC / PL / Fluct / Actual / Theo tiến độ nên chi; K$ 2 chữ số: vật tư, ngày, tổng trực tiếp).
//
// Chung:
//   Tiến độ, dự báo (cách Excel), rủi ro: CostMonitoringAssessment
//   Top 3 ngày chi lớn nhất đến ngày D (ngày|K$|% của Actual), từ Act MTD của chart
//   T&SL / RM: top 3 vật tư xuất trong ngày lớn nhất (chi phí trực tiếp), số dòng xuất trong ngày,
//     tỷ trọng 3 vật tư = tổng 3 dòng / chi phí trực tiếp của ngày đó.
//     Luôn xuất (đặt trước phần so cùng kỳ để không bị cắt khi kết quả dài).
//   T&SL / RM: "Khoản lớn bất thường" (xem appendUnusual), cũng đặt trước phần so cùng kỳ.
//   Kịch bản cuối tháng: CmText.scenarioParts (giống context).
//   Vật tư hiển thị "Tên (Code)", tên từ [Text] cắt tối đa 40 ký tự (CmText.itemLabel).
// RM, T&SL: so cùng kỳ tháng trước theo Category và theo vật tư (Code + Text):
//   top 5 tăng mạnh nhất, top 3 giảm mạnh nhất:
//   tên|tháng này|cùng kỳ tháng trước|chênh (K$)|chênh / Actual (%)
//   Tỷ lệ giải thích (Coverage) = tổng mức tăng top 5 vật tư / mức chênh cần giải thích × 100
//     (mức chênh = Chi vượt tiến độ ở IN_PROGRESS, Gap ở OVER)
//   Kỳ so sánh: ngày 1 -> ngày có Actual mới nhất của khung (cùng mốc với Act).
// Dòng cuối: tổng chi phí trực tiếp (bảng chi tiết) và Act − trực tiếp
//   (= chi phí phân bổ F2/HT2/HT-G + điều chỉnh).
// =========================================================
@Component
@RequiredArgsConstructor
public class ExplainVarianceTool implements AssistantTool {

	// Chế độ OVER
	static final String CAUSE_TARGET_CUT = "TARGET_CUT";
	static final String CAUSE_OVER_SPEND = "OVER_SPEND";
	static final String CAUSE_BOTH = "BOTH";
	static final String CAUSE_NONE = "NONE";

	// Chế độ IN_PROGRESS
	static final String CAUSE_EARLY_OVERSPEND = "EARLY_OVERSPEND";
	static final String CAUSE_ON_TRACK = "ON_TRACK";

	static final String CAUSE_NO_DATA = "NO_DATA";

	// Mỗi phần ≥ 30% Gap -> BOTH
	private static final double BOTH_SHARE = 0.3;

	// |Gap| nhỏ hơn mức này (K$) coi như bằng target
	private static final double GAP_EPSILON = 0.05;

	private static final List<String> DETAIL_COSTS = List.of("T&SL", "RM");
	private static final int TOP_INCREASE = 5;
	private static final int TOP_DECREASE = 3;
	private static final int TOP_DAYS = 3;
	private static final int TOP_DAY_ITEMS = 3;

	// Khoản lớn bất thường: 1 dòng ≥ 10% Act của khung (hoặc khớp từ khóa), tối đa 5 dòng
	private static final double UNUSUAL_SHARE = 0.10;
	private static final int MAX_UNUSUAL = 5;

	// div=ALL: mỗi bộ phận chỉ top 3 vật tư tăng; kết quả tối đa 5000 ký tự
	private static final int TOP_INCREASE_ALL = 3;
	private static final int MAX_RESULT_LENGTH_ALL = 5000;

	private final CostMonitoringSnapshotLoader loader;
	private final CostTopItemsRepository repository;

	@Override
	public String name() {
		return "explain_variance";
	}

	@Override
	public String description() {
		return "Phân tích vì sao 1 khung vượt, có nguy cơ vượt hoặc thấp hơn Fluctuating TGT: "
				+ "khi chưa vượt thì so Actual với tiến độ (chi vượt tiến độ), khi đã vượt thì tách phần target bị hạ "
				+ "và phần chi vượt FC; kèm tiến độ, dự báo, rủi ro, kết luận chính, 3 ngày chi lớn nhất; "
				+ "với T&SL / RM so cùng kỳ tháng trước theo Category và vật tư. Dùng cho câu hỏi \"vì sao vượt / thấp\".";
	}

	@Override
	public Map<String, Object> parameters() {
		return CostMonitoringToolArgs.divCostSchema(
				CostMonitoringToolArgs.DIVS_WITH_ALL,
				"Bộ phận; ALL = cả 3 bộ phận (dùng khi hỏi nhiều bộ phận)",
				CostMonitoringSnapshot.COSTS,
				Map.of()
		);
	}

	// div=ALL: kết quả gồm 3 bộ phận -> cho phép dài hơn
	@Override
	public int maxResultLength(Map<String, Object> args) {

		Object div = args.get("div");

		return div != null && CostMonitoringToolArgs.ALL.equalsIgnoreCase(div.toString().trim())
				? MAX_RESULT_LENGTH_ALL
				: DEFAULT_MAX_RESULT_LENGTH;
	}

	@Override
	public String execute(Map<String, Object> args, Map<String, String> screenParams, String lang) {

		String div = CostMonitoringToolArgs.divOrAll(args);
		String cost = CostMonitoringToolArgs.cost(args, CostMonitoringSnapshot.COSTS);

		CostMonitoringSnapshot snapshot = loader.load(screenParams);

		return CostMonitoringToolArgs.ALL.equals(div)
				? executeAll(snapshot, cost, lang)
				: executeOne(snapshot, div, cost, lang);
	}

	// =========================================================
	// 1 BỘ PHẬN: phân tích đầy đủ
	// =========================================================

	private String executeOne(CostMonitoringSnapshot snapshot, String div, String cost, String lang) {

		CostChartDto chart = snapshot.find(div, cost);

		String title = div + " " + cost + t(lang, "|Tháng=", "|Month=") + snapshot.month();

		if (chart == null) {
			return title + "|" + t(lang, "Không có dữ liệu", "No data");
		}

		int d = snapshot.d();
		int n = snapshot.n();

		CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);

		Double fc = chart.fcTgt();
		Double pl = chart.plTgt();
		Double act = a.act();
		Double fluct = a.fluct();

		StringBuilder sb = new StringBuilder();

		sb.append(title)
				.append(t(lang, "|Trạng thái tháng=", "|Month status=")).append(CmText.monthStatus(a.closed(), lang))
				.append(t(lang, "|Đến ngày (D)=", "|Up to day (D)=")).append(d)
				.append(t(lang, "|Số ngày trong tháng (N)=", "|Days in month (N)=")).append(n)
				.append(t(lang, "|Đơn vị=K$", "|Unit=K$"))
				.append('\n');

		sb.append("FC=").append(CmText.k1(fc))
				.append("|Fluct=").append(CmText.k1(fluct))
				.append("|PL=").append(CmText.k1(pl))
				.append("|Actual=").append(CmText.k1(act))
				.append('\n');

		CoverageBase coverage = appendAnalysis(sb, a, chart, d, n, lang);

		if (a.closed()) {
			// Tháng đã kết thúc: chỉ kết quả tháng, không tiến độ / dự báo / rủi ro / kịch bản
			sb.append(t(lang, "Đã dùng % Fluct=", "Used % of Fluct=")).append(CmText.pct0(a.pct()))
					.append(t(lang, "|Kết quả tháng=", "|Month result=")).append(CmText.monthResult(a, lang))
					.append('\n');
		} else {
			// Tiến độ / dự báo / rủi ro: cùng cách tính với context
			sb.append(t(lang, "Đã dùng % Fluct=", "Used % of Fluct=")).append(CmText.pct0(a.pct()))
					.append(t(lang, "|Tiến độ chuẩn=", "|Standard pace=")).append(CmText.pct0(a.pace()))
					.append(t(lang, "|Tiến độ=", "|Pace=")).append(CmText.pace(a.paceStatus(), lang))
					.append('\n');

			String forecast = CmText.k1(a.forecast());
			String base = CmText.forecastBase(a.forecastBase(), lang);

			sb.append(t(lang, "Dự báo cuối tháng=", "Month-end forecast="))
					.append(forecast.isEmpty() || base.isEmpty() ? forecast : forecast + " (" + base + ")")
					.append(t(lang, "|Kết luận dự báo=", "|Forecast outlook=")).append(CmText.forecastOutlook(a, lang))
					.append(t(lang, "|Rủi ro=", "|Risk=")).append(CmText.risk(a.risk(), lang))
					.append('\n');

			appendScenarios(sb, a, lang);
		}

		appendTopDays(sb, snapshot, chart, div, cost, act, lang);

		if (DETAIL_COSTS.contains(cost)) {
			appendUnusual(sb, snapshot, chart, div, cost, a, lang);
			appendDetail(sb, snapshot, chart, div, cost, act, coverage.value(), coverage.label(), lang);
		} else {
			sb.append(t(lang, "Chi tiết vật tư: không có (OT)", "Item detail: none (OT)"));
		}

		return sb.toString().trim();
	}


	// =========================================================
	// ALL: 3 bộ phận cùng loại chi phí, mỗi bộ phận bản rút gọn
	//   chế độ + kết luận chính + chi vượt tiến độ / phần vượt Fluct, dự báo, rủi ro,
	//   top 3 ngày, top 3 vật tư ngày lớn nhất + tỷ trọng, top 3 vật tư tăng so với cùng kỳ
	// =========================================================

	private String executeAll(CostMonitoringSnapshot snapshot, String cost, String lang) {

		int d = snapshot.d();
		int n = snapshot.n();

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "Tất cả bộ phận ", "All divisions ")).append(cost)
				.append(t(lang, "|Tháng=", "|Month=")).append(snapshot.month())
				.append(t(lang, "|Trạng thái tháng=", "|Month status=")).append(CmText.monthStatus(snapshot.monthClosed(), lang))
				.append(t(lang, "|Đến ngày (D)=", "|Up to day (D)=")).append(d)
				.append(t(lang, "|Số ngày trong tháng (N)=", "|Days in month (N)=")).append(n)
				.append(t(lang, "|Đơn vị=K$", "|Unit=K$"))
				.append('\n');

		for (String div : CostMonitoringSnapshot.DIVS) {

			sb.append("\n== ").append(div).append(' ').append(cost).append(" ==\n");

			CostChartDto chart = snapshot.find(div, cost);

			if (chart == null) {
				sb.append(t(lang, "Không có dữ liệu", "No data")).append('\n');
				continue;
			}

			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);

			sb.append("FC=").append(CmText.k1(chart.fcTgt()))
					.append("|Fluct=").append(CmText.k1(a.fluct()))
					.append("|PL=").append(CmText.k1(chart.plTgt()))
					.append("|Actual=").append(CmText.k1(a.act()))
					.append('\n');

			appendAnalysis(sb, a, chart, d, n, lang);

			if (a.closed()) {
				sb.append(t(lang, "Kết quả tháng=", "Month result=")).append(CmText.monthResult(a, lang))
						.append('\n');
			} else {
				sb.append(t(lang, "Kết luận dự báo=", "Forecast outlook=")).append(CmText.forecastOutlook(a, lang))
						.append(t(lang, "|Rủi ro=", "|Risk=")).append(CmText.risk(a.risk(), lang))
						.append('\n');

				appendScenarios(sb, a, lang);
			}

			appendTopDays(sb, snapshot, chart, div, cost, a.act(), lang);

			if (DETAIL_COSTS.contains(cost)) {
				appendUnusual(sb, snapshot, chart, div, cost, a, lang);
				appendTopIncreases(sb, snapshot, chart, div, cost, a.act(), lang);
			}
		}

		return sb.toString().trim();
	}

	// Top 3 vật tư tăng mạnh nhất so với cùng kỳ tháng trước (bản rút gọn cho ALL)
	private void appendTopIncreases(
			StringBuilder sb,
			CostMonitoringSnapshot snapshot,
			CostChartDto chart,
			String div,
			String cost,
			Double act,
			String lang
	) {

		ComparePeriod period = comparePeriod(snapshot, chart);

		if (period == null) {
			return;
		}

		List<String> costCenters = CostMonitoringSnapshot.DIV_COST_CENTERS.get(div);

		appendChanges(
				sb,
				t(lang, "Vật tư", "Item"),
				byItemLabel(repository.sumByItem(cost, period.curFrom(), period.curTo(), costCenters), lang),
				byItemLabel(repository.sumByItem(cost, period.prevFrom(), period.prevTo(), costCenters), lang),
				act,
				lang,
				TOP_INCREASE_ALL,
				0
		);
	}


	// =========================================================
	// KỊCH BẢN CUỐI THÁNG (dùng chung 1 bộ phận / ALL, cùng nội dung với context)
	//
	//   "Nếu từ nay chi đúng target" = Act + Fluct / N × (N − D)
	//   "Nếu giữ tốc độ hiện tại"    = Act / D × N
	//   Mỗi kịch bản so với Fluct và FC, có dấu (code tự so sánh)
	//   "Ngân sách còn lại mỗi ngày" = (Fluct − Act) / (N − D) so với "Nhịp target mỗi ngày" = Fluct / N
	// =========================================================

	private static void appendScenarios(StringBuilder sb, CostMonitoringAssessment a, String lang) {

		for (String part : CmText.scenarioParts(a, lang)) {
			sb.append(part).append('\n');
		}
	}


	// =========================================================
	// KHOẢN LỚN BẤT THƯỜNG (T&SL / RM)
	//
	//   1 dòng xuất kho đơn lẻ (Amt > 0) trong kỳ ngày 1 -> ngày có Actual mới nhất, và:
	//     Amt ≥ 10% Act của khung, hoặc
	//     Text / Category chứa license, renewal, software, bản quyền, phần mềm (CostTopItemsRepository)
	//   Cột: Tên (Code)|ngày|K$|% Act. Tối đa MAX_UNUSUAL dòng, lớn trước.
	//   "Chi vượt tiến độ nếu bỏ khoản lớn bất thường" = (Act − tổng các khoản) − Fluct × D / N
	//   "Tiến độ nếu bỏ khoản lớn bất thường": cùng ngưỡng ± 3 điểm % như Tiến độ.
	//   Không có khoản nào -> 1 dòng "(không có)".
	// =========================================================

	private void appendUnusual(
			StringBuilder sb,
			CostMonitoringSnapshot snapshot,
			CostChartDto chart,
			String div,
			String cost,
			CostMonitoringAssessment a,
			String lang
	) {

		ComparePeriod period = comparePeriod(snapshot, chart);

		if (period == null) {
			return;
		}

		Double act = a.act();
		Double minAmountK = act != null && act > 0 ? act * UNUSUAL_SHARE : null;

		List<CostLineDto> lines = repository.findUnusualLines(
				cost,
				period.curFrom(),
				period.curTo(),
				CostMonitoringSnapshot.DIV_COST_CENTERS.get(div),
				minAmountK,
				MAX_UNUSUAL
		);

		sb.append(t(lang,
				"[Khoản lớn bất thường: 1 dòng ≥ 10% Actual hoặc license/renewal/software/bản quyền/phần mềm] cột=Tên (Code)|ngày|K$|% Act",
				"[Unusual large items: single line ≥ 10% of Actual or license/renewal/software] cols=Name (Code)|day|K$|% Act"))
				.append('\n');

		if (lines.isEmpty()) {
			sb.append(CmText.none(lang)).append('\n');
			return;
		}

		// Cộng từ số hiển thị (2 chữ số)
		double total = 0;

		for (CostLineDto line : lines) {

			double amount = line.amountK() != null ? CmText.r2(line.amountK()) : 0.0;
			total = CmText.r2(total + amount);

			sb.append(CmText.itemLabel(line.code(), line.name(), lang))
					.append('|').append(line.date() != null ? line.date().toString() : "")
					.append('|').append(k(amount))
					.append('|').append(pctOfAct(amount, act))
					.append('\n');
		}

		int d = snapshot.d();
		int n = snapshot.n();
		Double fluct = a.fluct();

		// Act (1 chữ số) − tổng khoản (2 chữ số), giống số hiển thị
		Double actWithout = act != null ? CmText.r2(CmText.r1(act) - total) : null;

		// Tháng đã kết thúc: không có tiến độ -> so phần còn lại với Fluct cả tháng
		if (a.closed()) {
			sb.append(t(lang, "Tổng khoản lớn bất thường=", "Unusual items total=")).append(k(total))
					.append(t(lang, "|Kết quả tháng nếu bỏ khoản lớn bất thường (Act − khoản − Fluct)=",
							"|Month result excluding unusual items (Act − items − Fluct)="))
					.append(CmText.signedK(subRounded(actWithout, CmText.r1(fluct)),
							t(lang, "phần còn lại vẫn vượt Fluct", "the rest is still above Fluct"),
							t(lang, "phần còn lại trong Fluct", "the rest is within Fluct")))
					.append('\n');
			sb.append(t(lang,
					"Ghi chú: khoản lớn bất thường vẫn tính vào ngân sách tháng (Actual, Fluct không loại trừ).",
					"Note: unusual items still count toward the monthly budget (Actual and Fluct do not exclude them)."))
					.append('\n');
			return;
		}

		// (Act − tổng khoản) − Theo tiến độ nên chi (1 chữ số, như dòng "Theo tiến độ nên chi")
		Double excessWithout = fluct != null && n > 0 ? subRounded(actWithout, CmText.r1(fluct * d / n)) : null;
		Double fluctR = CmText.r1(fluct);
		Double pctWithout = actWithout != null && fluctR != null && fluctR != 0 ? actWithout / fluctR * 100 : null;

		sb.append(t(lang, "Tổng khoản lớn bất thường=", "Unusual items total=")).append(k(total))
				.append(t(lang, "|Chi vượt tiến độ nếu bỏ khoản lớn bất thường=", "|Excess vs pace excluding unusual items="))
				.append(CmText.signedK(excessWithout,
						t(lang, "phần còn lại chi nhiều hơn tiến độ", "the rest is ahead of pace"),
						t(lang, "phần còn lại chi ít hơn tiến độ", "the rest is behind pace")))
				.append(t(lang, "|Tiến độ nếu bỏ khoản lớn bất thường=", "|Pace excluding unusual items="))
				.append(CmText.pace(CostMonitoringAssessment.paceStatus(pctWithout, a.pace()), lang))
				.append('\n');

		sb.append(t(lang,
				"Ghi chú: khoản lớn bất thường vẫn tính vào ngân sách tháng (Actual, Fluct, dự báo không loại trừ).",
				"Note: unusual items still count toward the monthly budget (Actual, Fluct and forecasts do not exclude them)."))
				.append('\n');
	}


	// =========================================================
	// PHÂN TÍCH THEO CHẾ ĐỘ (dùng chung 1 bộ phận / ALL)
	// =========================================================

	// Mẫu số của tỷ lệ giải thích: Chi vượt tiến độ (IN_PROGRESS) hoặc Phần vượt Fluct (OVER)
	private record CoverageBase(Double value, String label) {
	}

	private static CoverageBase appendAnalysis(
			StringBuilder sb,
			CostMonitoringAssessment a,
			CostChartDto chart,
			int d,
			int n,
			String lang
	) {

		Double fc = chart.fcTgt();
		Double pl = chart.plTgt();
		Double act = a.act();
		Double fluct = a.fluct();

		String diffVsPl = CmText.signedK(CmText.subR1(act, pl),
				t(lang, "cao hơn PL", "above PL"),
				t(lang, "thấp hơn PL", "below PL"));

		Double coverageBase = null;
		String coverageBaseLabel = null;

		if (CostMonitoringAssessment.MODE_IN_PROGRESS.equals(a.mode())) {

			Double excess = a.excessVsPace();
			Double shouldSpend = n > 0 ? fluct * d / n : null;
			String cause = excess == null ? CAUSE_NO_DATA : excess > 0 ? CAUSE_EARLY_OVERSPEND : CAUSE_ON_TRACK;

			sb.append(t(lang, "Chế độ phân tích=", "Analysis mode=")).append(CmText.mode(a.mode(), lang))
					.append(t(lang, "|Kết luận chính=", "|Main cause=")).append(CmText.mainCause(cause, lang))
					.append('\n');

			sb.append(t(lang, "Theo tiến độ nên chi=", "Should have spent by pace=")).append(CmText.k1(shouldSpend))
					.append(t(lang, "|Chi vượt tiến độ=", "|Excess vs pace="))
					.append(CmText.signedK(excess,
							t(lang, "chi nhiều hơn tiến độ", "spending ahead of pace"),
							t(lang, "chi ít hơn tiến độ", "spending behind pace")))
					.append(t(lang, "|Chênh so với PL=", "|Difference vs PL=")).append(diffVsPl)
					.append('\n');

			coverageBase = excess;
			coverageBaseLabel = t(lang, "so với Chi vượt tiến độ", "of Excess vs pace");

		} else if (CostMonitoringAssessment.MODE_OVER.equals(a.mode())) {

			// Từ số đã làm tròn 1 chữ số: Gap = TargetCut + OverFC đúng tuyệt đối trên số hiển thị
			Double gap = CmText.subR1(act, fluct);
			Double targetCut = CmText.subR1(fc, fluct);
			Double overFc = CmText.subR1(act, fc);

			sb.append(t(lang, "Chế độ phân tích=", "Analysis mode=")).append(CmText.mode(a.mode(), lang))
					.append(t(lang, "|Kết luận chính=", "|Main cause="))
					.append(CmText.mainCause(mainCause(gap, targetCut, overFc), lang))
					.append('\n');

			// Badge (% so với Fluct, khớp FE) và % so với FC = Actual / FC × 100
			sb.append(t(lang, "Badge (% so với Fluct)=", "Badge (% of Fluct)=")).append(CmText.pct0(a.pct()))
					.append(t(lang, "|% so với FC (Actual / FC)=", "|% of FC (Actual / FC)="))
					.append(CmText.pct0(act != null && fc != null && fc != 0 ? act / fc * 100 : null))
					.append('\n');

			// Gap = Act − Fluct = TargetCut (FC − Fluct) + OverFC (Act − FC)
			sb.append(t(lang, "Phần vượt Fluct (Act − Fluct)=", "Over Fluct (Act − Fluct)="))
					.append(CmText.signedK(gap, t(lang, "vượt Fluct", "above Fluct"), t(lang, "dưới Fluct", "below Fluct")))
					.append(t(lang, "|Target bị hạ (FC − Fluct)=", "|Target cut (FC − Fluct)="))
					.append(CmText.signedK(targetCut,
							t(lang, "target bị hạ do doanh số/tồn kho", "target cut due to sales/inventory"),
							t(lang, "target được nâng", "target raised")))
					.append(t(lang, "|Chi vượt FC (Act − FC)=", "|Spending above FC (Act − FC)="))
					.append(CmText.signedK(overFc,
							t(lang, "chi vượt FC", "above FC"),
							t(lang, "chi dưới FC", "below FC")))
					.append(t(lang, "|Chênh so với PL=", "|Difference vs PL=")).append(diffVsPl)
					.append('\n');

			// Tỷ trọng do target bị hạ = (FC − Fluct) / Phần vượt Fluct × 100 (âm = target được nâng)
			sb.append(t(lang,
							"Tỷ trọng do target bị hạ ((FC − Fluct) / Phần vượt Fluct)=",
							"Share due to target cut ((FC − Fluct) / Over Fluct)="))
					.append(targetCutShare(targetCut, gap))
					.append(t(lang, "|Tỷ trọng do chi vượt FC ((Act − FC) / Phần vượt Fluct)=",
							"|Share due to spending above FC ((Act − FC) / Over Fluct)="))
					.append(targetCutShare(overFc, gap))
					.append('\n');

			coverageBase = gap;
			coverageBaseLabel = t(lang, "so với Phần vượt Fluct", "of Over Fluct");

		} else if (CostMonitoringAssessment.MODE_CLOSED_WITHIN.equals(a.mode())) {

			// Tháng đã kết thúc, Act ≤ Fluct: không phân tích tiến độ
			sb.append(t(lang, "Chế độ phân tích=", "Analysis mode=")).append(CmText.mode(a.mode(), lang))
					.append(t(lang, "|Kết luận chính=", "|Main cause=")).append(CmText.monthResult(a, lang))
					.append('\n');

			sb.append(t(lang, "Chênh so với Fluct (Act − Fluct)=", "Difference vs Fluct (Act − Fluct)="))
					.append(CmText.signedK(CmText.subR1(act, fluct), t(lang, "vượt Fluct", "above Fluct"), t(lang, "dưới Fluct", "below Fluct")))
					.append(t(lang, "|Chênh so với FC (Act − FC)=", "|Difference vs FC (Act − FC)="))
					.append(CmText.signedK(CmText.subR1(act, fc), t(lang, "chi vượt FC", "above FC"), t(lang, "chi dưới FC", "below FC")))
					.append(t(lang, "|Chênh so với PL=", "|Difference vs PL=")).append(diffVsPl)
					.append('\n');

		} else {
			sb.append(t(lang, "Chế độ phân tích=", "Analysis mode=")).append(CmText.noData(lang))
					.append(t(lang, "|Kết luận chính=", "|Main cause=")).append(CmText.noData(lang))
					.append('\n');
		}

		return new CoverageBase(coverageBase, coverageBaseLabel);
	}


	// =========================================================
	// TOP NGÀY
	// =========================================================

	private record DayAmount(LocalDate date, double amount) {
	}

	// Chi phí từng ngày = Act MTD hôm đó − Act MTD gần nhất trước đó (đến ngày D)
	private void appendTopDays(
			StringBuilder sb,
			CostMonitoringSnapshot snapshot,
			CostChartDto chart,
			String div,
			String cost,
			Double act,
			String lang
	) {

		List<DayAmount> days = new ArrayList<>();

		if (snapshot.d() >= 1) {

			LocalDate until = snapshot.month().atDay(snapshot.d());
			double lastMtd = 0.0;

			for (CostChartPointDto point : chart.points()) {
				if (point.date() == null || point.date().isAfter(until) || point.actMtd() == null) {
					continue;
				}
				days.add(new DayAmount(point.date(), point.actMtd() - lastMtd));
				lastMtd = point.actMtd();
			}
		}

		List<DayAmount> top = days.stream()
				.sorted(Comparator.comparingDouble(DayAmount::amount).reversed())
				.limit(TOP_DAYS)
				.toList();

		sb.append(t(lang,
				"[3 ngày chi lớn nhất] cột=ngày|K$|% của Actual",
				"[Top 3 spending days] cols=day|K$|% of Actual")).append('\n');

		if (top.isEmpty()) {
			sb.append(CmText.none(lang)).append('\n');
			return;
		}

		for (DayAmount day : top) {
			sb.append(day.date())
					.append('|').append(k(day.amount()))
					.append('|').append(pctOfAct(day.amount(), act))
					.append('\n');
		}

		// Vật tư xuất trong ngày lớn nhất (chỉ có chi tiết cho T&SL / RM)
		if (!DETAIL_COSTS.contains(cost)) {
			return;
		}

		LocalDate peak = top.get(0).date();
		List<String> costCenters = CostMonitoringSnapshot.DIV_COST_CENTERS.get(div);

		List<CostTopItemDto> items = repository.findTopItems(cost, peak, peak, costCenters, TOP_DAY_ITEMS);
		int rowCount = repository.countRows(cost, peak, peak, costCenters);
		Double dayDirect = repository.findTotal(cost, peak, peak, costCenters);

		// Tổng từ số hiển thị (2 chữ số)
		double topSum = CmText.r2(items.stream()
				.mapToDouble(item -> item.amountK() != null ? CmText.r2(item.amountK()) : 0.0)
				.sum());

		sb.append(t(lang, "[Vật tư xuất ngày ", "[Items issued on ")).append(peak)
				.append(t(lang,
						", chỉ chi phí trực tiếp] cột=Vật tư (Code)|K$",
						", direct cost only] cols=Item (Code)|K$"))
				.append('\n');

		if (items.isEmpty()) {
			sb.append(CmText.none(lang)).append('\n');
		}

		for (CostTopItemDto item : items) {
			sb.append(CmText.itemLabel(item.code(), item.name(), lang))
					.append('|').append(k(item.amountK()))
					.append('\n');
		}

		// Tỷ trọng 3 vật tư = tổng 3 dòng / chi phí trực tiếp của ngày đó
		sb.append(t(lang, "Số dòng vật tư xuất trong ngày=", "Item lines issued that day=")).append(rowCount)
				.append(t(lang, "|Chi phí trực tiếp của ngày=", "|Direct cost that day=")).append(k(dayDirect))
				.append(t(lang, "|Tỷ trọng 3 vật tư trong ngày=", "|Share of top 3 items that day="))
				.append(share(topSum, CmText.r2(dayDirect)))
				.append('\n');
	}


	// =========================================================
	// SO CÙNG KỲ THÁNG TRƯỚC (T&SL / RM)
	// =========================================================

	private void appendDetail(
			StringBuilder sb,
			CostMonitoringSnapshot snapshot,
			CostChartDto chart,
			String div,
			String cost,
			Double act,
			Double coverageBase,
			String coverageBaseLabel,
			String lang
	) {

		ComparePeriod period = comparePeriod(snapshot, chart);

		if (period == null) {
			sb.append(t(lang, "Chi tiết vật tư: không có", "Item detail: none"));
			return;
		}

		LocalDate curFrom = period.curFrom();
		LocalDate curTo = period.curTo();
		LocalDate prevFrom = period.prevFrom();
		LocalDate prevTo = period.prevTo();

		List<String> costCenters = CostMonitoringSnapshot.DIV_COST_CENTERS.get(div);

		// Kỳ so sánh, chỉ chi phí trực tiếp (chưa gồm phân bổ)
		sb.append(t(lang, "So cùng kỳ: tháng này=", "Same-period comparison: this month="))
				.append(curFrom).append("..").append(curTo)
				.append(t(lang, "|cùng kỳ tháng trước=", "|same period last month="))
				.append(prevFrom).append("..").append(prevTo)
				.append(t(lang, "|chỉ chi phí trực tiếp", "|direct cost only"))
				.append('\n');

		appendChanges(
				sb,
				t(lang, "Category", "Category"),
				repository.sumByCategory(cost, curFrom, curTo, costCenters),
				repository.sumByCategory(cost, prevFrom, prevTo, costCenters),
				act,
				lang,
				TOP_INCREASE,
				TOP_DECREASE
		);

		double itemIncrease = appendChanges(
				sb,
				t(lang, "Vật tư", "Item"),
				byItemLabel(repository.sumByItem(cost, curFrom, curTo, costCenters), lang),
				byItemLabel(repository.sumByItem(cost, prevFrom, prevTo, costCenters), lang),
				act,
				lang,
				TOP_INCREASE,
				TOP_DECREASE
		);

		// Tỷ lệ giải thích = tổng mức tăng top 5 vật tư / (Chi vượt tiến độ hoặc Phần vượt Fluct); mẫu số ≤ 0 -> trống
		sb.append(t(lang, "Tổng mức tăng của 5 vật tư tăng mạnh nhất=", "Total increase of top 5 rising items="))
				.append(CmText.signedK(itemIncrease))
				.append(t(lang, "|Tỷ lệ giải thích=", "|Share explained="))
				.append(coverage(itemIncrease, coverageBase));
		if (coverageBaseLabel != null && !coverage(itemIncrease, coverageBase).isEmpty()) {
			sb.append(" (").append(coverageBaseLabel).append(')');
		}
		sb.append('\n');

		Double direct = repository.findTotal(cost, curFrom, curTo, costCenters);

		// Act − tổng trực tiếp = phân bổ F2/HT2/HT-G + điều chỉnh
		sb.append(t(lang, "Tổng chi phí trực tiếp đến ", "Total direct cost up to ")).append(curTo)
				.append('=').append(k(direct))
				.append(t(lang,
						"|Phân bổ F2/HT2/HT-G + điều chỉnh (Actual − trực tiếp)=",
						"|F2/HT2/HT-G allocation + adjustments (Actual − direct)="))
				.append(k(subRounded(CmText.r1(act), CmText.r2(direct))));
	}

	// Kỳ so sánh: ngày 1 -> ngày có Actual mới nhất của khung (T&SL ~ hôm qua, RM ~ hôm kia;
	// chưa có actual thì dùng D), và cùng kỳ tháng trước. Không có ngày nào -> null.
	private record ComparePeriod(LocalDate curFrom, LocalDate curTo, LocalDate prevFrom, LocalDate prevTo) {
	}

	private static ComparePeriod comparePeriod(CostMonitoringSnapshot snapshot, CostChartDto chart) {

		int lastDay = snapshot.lastActualDay(chart);
		int day = lastDay > 0 ? lastDay : snapshot.d();

		if (day < 1) {
			return null;
		}

		YearMonth month = snapshot.month();
		YearMonth prevMonth = month.minusMonths(1);
		int prevDay = Math.min(day, prevMonth.lengthOfMonth());

		return new ComparePeriod(
				month.atDay(1),
				month.atDay(day),
				prevMonth.atDay(1),
				prevMonth.atDay(prevDay)
		);
	}

	// Vật tư -> K$, khóa là nhãn "Tên (Code)" (trùng nhãn thì cộng dồn)
	private static Map<String, Double> byItemLabel(List<CostItemAmountDto> rows, String lang) {

		Map<String, Double> map = new LinkedHashMap<>();

		for (CostItemAmountDto row : rows) {
			map.merge(
					CmText.itemLabel(row.code(), row.name(), lang),
					row.amountK() != null ? row.amountK() : 0.0,
					Double::sum
			);
		}

		return map;
	}

	private record Change(String label, double current, double previous) {

		double diff() {
			return CmText.r2(current - previous);
		}
	}

	// Ghi mục tăng (tối đa upLimit dòng) và giảm (tối đa downLimit; 0 = bỏ mục giảm);
	// trả về tổng mức tăng của các dòng tăng đã ghi
	private static double appendChanges(
			StringBuilder sb,
			String title,
			Map<String, Double> current,
			Map<String, Double> previous,
			Double act,
			String lang,
			int upLimit,
			int downLimit
	) {

		Set<String> labels = new HashSet<>(current.keySet());
		labels.addAll(previous.keySet());

		List<Change> changes = new ArrayList<>();

		for (String label : labels) {
			// Số hiển thị 2 chữ số -> chênh = tháng này − cùng kỳ trên chính các số đó
			changes.add(new Change(
					label,
					CmText.r2(current.getOrDefault(label, 0.0)),
					CmText.r2(previous.getOrDefault(label, 0.0))
			));
		}

		List<Change> increases = changes.stream()
				.filter(c -> c.diff() > 0)
				.sorted(Comparator.comparingDouble(Change::diff).reversed())
				.limit(upLimit)
				.toList();

		String cols = t(lang,
				"] cột=tên|tháng này|cùng kỳ tháng trước|chênh|chênh / Actual",
				"] cols=name|this month|same period last month|change|change / Actual");

		sb.append('[').append(title).append(t(lang, " tăng mạnh nhất", " rising most")).append(cols).append('\n');
		appendRows(sb, increases, act, lang);

		if (downLimit > 0) {

			List<Change> decreases = changes.stream()
					.filter(c -> c.diff() < 0)
					.sorted(Comparator.comparingDouble(Change::diff))
					.limit(downLimit)
					.toList();

			sb.append('[').append(title).append(t(lang, " giảm mạnh nhất", " falling most")).append(cols).append('\n');
			appendRows(sb, decreases, act, lang);
		}

		return CmText.r2(increases.stream().mapToDouble(Change::diff).sum());
	}

	private static void appendRows(StringBuilder sb, List<Change> rows, Double act, String lang) {

		if (rows.isEmpty()) {
			sb.append(CmText.none(lang)).append('\n');
			return;
		}

		for (Change row : rows) {
			sb.append(clean(row.label()))
					.append('|').append(k(row.current()))
					.append('|').append(k(row.previous()))
					.append('|').append(signed(row.diff()))
					.append('|').append(pctOfAct(row.diff(), act))
					.append('\n');
		}
	}


	// =========================================================
	// TÍNH TOÁN
	// =========================================================

	// Chế độ OVER
	static String mainCause(Double gap, Double targetCut, Double overFc) {

		if (gap == null || targetCut == null || overFc == null) {
			return CAUSE_NO_DATA;
		}

		if (Math.abs(gap) < GAP_EPSILON) {
			return CAUSE_NONE;
		}

		// Tỷ trọng mỗi phần trong Gap (cùng dấu với Gap -> dương)
		double targetShare = targetCut / gap;
		double spendShare = overFc / gap;

		if (targetShare >= BOTH_SHARE && spendShare >= BOTH_SHARE) {
			return CAUSE_BOTH;
		}

		return targetShare >= spendShare ? CAUSE_TARGET_CUT : CAUSE_OVER_SPEND;
	}

	// "x%" 1 chữ số; mẫu số không có hoặc ≤ 0 -> trống
	private static String coverage(double increase, Double base) {
		return share(increase, base);
	}

	// part / whole × 100, "x%" 1 chữ số; whole không có hoặc ≤ 0 -> trống
	private static String share(double part, Double whole) {

		if (whole == null || whole <= 0) {
			return "";
		}

		return String.format(Locale.ROOT, "%.1f%%", part / whole * 100);
	}

	// part / gap × 100, "x%" 1 chữ số, có dấu (âm = ngược chiều Gap); gap không có hoặc ≈ 0 -> trống
	private static String targetCutShare(Double part, Double gap) {

		if (part == null || gap == null || Math.abs(gap) < GAP_EPSILON) {
			return "";
		}

		return String.format(Locale.ROOT, "%.1f%%", part / gap * 100);
	}

	// a − b với a, b đã làm tròn; kết quả làm tròn 2 chữ số (bỏ sai số dấu phẩy động); thiếu số -> null
	private static Double subRounded(Double a, Double b) {
		return a == null || b == null ? null : CmText.r2(a - b);
	}

	// value / Act × 100, 1 chữ số, kèm "%"; tính từ số hiển thị (value 2 chữ số, Act 1 chữ số);
	// Act không có hoặc ≤ 0 -> trống
	static String pctOfAct(double value, Double act) {
		return share(CmText.r2(value), CmText.r1(act));
	}

	// K$ 2 chữ số
	private static String k(Double value) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}

		return String.format(Locale.ROOT, "%.2f", value);
	}

	private static String signed(double value) {
		return (value > 0 ? "+" : "") + String.format(Locale.ROOT, "%.2f", value);
	}

	private static String clean(String value) {
		return value == null ? "" : value.replace('|', '/').replaceAll("[\\r\\n]+", " ").trim();
	}
}
