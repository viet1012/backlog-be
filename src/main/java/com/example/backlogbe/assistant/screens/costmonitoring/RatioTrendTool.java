package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.tool.AssistantTool;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository.DailyRatio;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;
import static com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshot.fmt;

// =========================================================
// TOOL get_ratio_trend(div)
//
// Ratio Sale&Inven theo ngày của 1 bộ phận trong tháng đang xem, đọc từ
// [F2Database].[dbo].[F2_Working_Date]: Adj_Ratio_PR / _MO / _GU (+ Adj_Ratio_KVH toàn nhà máy để so sánh).
// Khoảng ngày: ngày 1 -> min(cuối tháng, hôm qua) (từ hôm nay trở đi SP dùng Ratio của hôm qua).
// Cột: ngày|thứ|WD_All|Ratio %|Ratio KVH %|Fluct OT|Fluct T&SL|Fluct RM
//   Ratio % = Adj_Ratio × 100 (có dấu); Fluct lấy từ SP (point.fluctTgt) của đúng ngày đó.
// Tóm tắt (code tính sẵn):
//   Ratio đầu tháng / mới nhất (giá trị khác null đầu tiên / cuối cùng) của bộ phận và KVH
//   Ngày đổi chiều: Ratio đổi dấu so với ngày có số liền trước (bỏ qua Ratio = 0)
//   Ngày tăng / giảm mạnh nhất: chênh (điểm %) lớn nhất / nhỏ nhất so với ngày có số liền trước
// Doanh số và tồn kho chi tiết: chưa có dữ liệu.
// =========================================================
@Component
@RequiredArgsConstructor
public class RatioTrendTool implements AssistantTool {

	private final CostMonitoringSnapshotLoader loader;
	private final CostWorkingDateRepository workingDateRepository;

	@Override
	public String name() {
		return "get_ratio_trend";
	}

	@Override
	public String description() {
		return "Lấy Ratio Sale&Inven (tỷ lệ điều chỉnh Fluct = FC × (1 + Ratio)) theo từng ngày của 1 bộ phận "
				+ "trong tháng đang xem, kèm Ratio toàn nhà máy (KVH), Fluct OT/T&SL/RM của từng ngày, "
				+ "Ratio đầu tháng, mới nhất, ngày đổi chiều, ngày tăng/giảm mạnh nhất. "
				+ "Dùng cho câu hỏi Fluct/target thay đổi thế nào, vì sao target bị hạ hay được nâng.";
	}

	@Override
	public Map<String, Object> parameters() {

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("div", Map.of(
				"type", "string",
				"enum", CostMonitoringSnapshot.DIVS,
				"description", "Bộ phận"
		));
		properties.put("month", CostMonitoringToolArgs.MONTH_PROPERTY);

		return Map.of(
				"type", "object",
				"properties", properties,
				"required", List.of("div")
		);
	}

	@Override
	public String execute(Map<String, Object> args, Map<String, String> screenParams, String lang) {

		String div = CostMonitoringToolArgs.div(args, screenParams);

		CostMonitoringSnapshot snapshot = loader.load(screenParams);
		String header = div + t(lang, "|Tháng=", "|Month=") + snapshot.month();

		LocalDate from = snapshot.month().atDay(1);
		LocalDate until = lastRatioDay(snapshot);

		if (until.isBefore(from)) {
			return header + "|" + t(lang, "Chưa có Ratio trong tháng", "No ratio yet this month");
		}

		TreeMap<LocalDate, DailyRatio> ratios = workingDateRepository.findRatios(from, until);

		Map<String, Map<LocalDate, Double>> fluctByCost = new LinkedHashMap<>();
		for (String cost : CostMonitoringSnapshot.COSTS) {
			fluctByCost.put(cost, fluctByDate(snapshot.find(div, cost)));
		}

		StringBuilder sb = new StringBuilder();

		sb.append(header)
				.append(t(lang, "|Đến ngày=", "|Up to day=")).append(until.getDayOfMonth())
				.append(t(lang, "|Đơn vị Fluct=K$", "|Fluct unit=K$"))
				.append('\n');

		if (ratios.isEmpty()) {
			sb.append(t(lang, "Không có Ratio trong F2_Working_Date cho tháng này.",
					"No ratio in F2_Working_Date for this month.")).append('\n');
			appendNotes(sb, lang);
			return sb.toString().trim();
		}

		sb.append(t(lang,
				"[Ratio theo ngày] cột=ngày|thứ|WD_All|Ratio %|Ratio KVH %|Fluct OT|Fluct T&SL|Fluct RM",
				"[Daily ratio] cols=day|weekday|WD_All|Ratio %|KVH ratio %|Fluct OT|Fluct T&SL|Fluct RM"))
				.append('\n');

		List<Map.Entry<LocalDate, Double>> divSeries = new ArrayList<>();
		List<Map.Entry<LocalDate, Double>> kvhSeries = new ArrayList<>();

		for (LocalDate date = from; !date.isAfter(until); date = date.plusDays(1)) {

			DailyRatio r = ratios.get(date);
			Double ratio = r == null ? null : ratioOf(r, div);
			Double kvh = r == null ? null : r.kvh();

			if (ratio != null) {
				divSeries.add(Map.entry(date, ratio));
			}
			if (kvh != null) {
				kvhSeries.add(Map.entry(date, kvh));
			}

			sb.append(String.format("%02d", date.getDayOfMonth()))
					.append('|').append(DailySeriesTool.weekday(date.getDayOfWeek(), lang))
					.append('|').append(DailySeriesTool.flagValue(r == null ? null : r.wdAll()))
					.append('|').append(pct(ratio))
					.append('|').append(pct(kvh));

			for (Map<LocalDate, Double> fluct : fluctByCost.values()) {
				sb.append('|').append(fmt(fluct.get(date)));
			}

			sb.append('\n');
		}

		appendSummary(sb, div, divSeries, kvhSeries, lang);
		appendNotes(sb, lang);

		return sb.toString().trim();
	}

	// Ngày cuối có Ratio dùng được: min(cuối tháng, hôm qua) — từ hôm nay SP dùng Ratio của hôm qua
	public static LocalDate lastRatioDay(CostMonitoringSnapshot snapshot) {

		LocalDate monthEnd = snapshot.month().atEndOfMonth();
		LocalDate yesterday = snapshot.today().minusDays(1);

		return yesterday.isBefore(monthEnd) ? yesterday : monthEnd;
	}

	// PRESS -> Adj_Ratio_PR, MOLD -> _MO, GUIDE -> _GU
	public static Double ratioOf(DailyRatio r, String div) {
		return switch (div) {
			case "PRESS" -> r.pr();
			case "MOLD" -> r.mo();
			case "GUIDE" -> r.gu();
			default -> null;
		};
	}

	// Ratio (0.05) -> "+5.0%"; null -> trống
	public static String pct(Double ratio) {
		return ratio == null || ratio.isNaN() || ratio.isInfinite()
				? ""
				: String.format(Locale.ROOT, "%+.1f%%", ratio * 100);
	}

	private static Map<LocalDate, Double> fluctByDate(CostChartDto chart) {

		Map<LocalDate, Double> result = new LinkedHashMap<>();

		if (chart == null) {
			return result;
		}

		for (CostChartPointDto point : chart.points()) {
			if (point.date() != null) {
				result.put(point.date(), point.fluctTgt());
			}
		}

		return result;
	}

	private static void appendSummary(
			StringBuilder sb,
			String div,
			List<Map.Entry<LocalDate, Double>> divSeries,
			List<Map.Entry<LocalDate, Double>> kvhSeries,
			String lang
	) {

		sb.append(t(lang, "[Tóm tắt]", "[Summary]")).append('\n');

		if (divSeries.isEmpty()) {
			sb.append(div).append(t(lang, ": không có Ratio", ": no ratio")).append('\n');
		} else {
			Map.Entry<LocalDate, Double> first = divSeries.get(0);
			Map.Entry<LocalDate, Double> last = divSeries.get(divSeries.size() - 1);

			sb.append(div)
					.append(t(lang, " Ratio đầu tháng=", " Ratio at month start=")).append(dayValue(first, lang))
					.append(t(lang, "|Ratio mới nhất=", "|Latest ratio=")).append(dayValue(last, lang))
					.append(t(lang, "|Thay đổi=", "|Change=")).append(points(pointsDiff(last.getValue(), first.getValue()), lang))
					.append('\n');

			sb.append(t(lang, "Ngày đổi chiều=", "Sign change days=")).append(signChanges(divSeries, lang))
					.append('\n');

			Map.Entry<LocalDate, Double> up = null;
			Map.Entry<LocalDate, Double> down = null;
			for (int i = 1; i < divSeries.size(); i++) {
				double delta = pointsDiff(divSeries.get(i).getValue(), divSeries.get(i - 1).getValue());
				LocalDate date = divSeries.get(i).getKey();
				if (delta > 0 && (up == null || delta > up.getValue())) {
					up = Map.entry(date, delta);
				}
				if (delta < 0 && (down == null || delta < down.getValue())) {
					down = Map.entry(date, delta);
				}
			}

			String none = t(lang, "không có", "none");
			sb.append(t(lang, "Ngày tăng mạnh nhất=", "Largest increase day="))
					.append(up == null ? none : day(up.getKey()) + " (" + points(up.getValue(), lang) + ")")
					.append(t(lang, "|Ngày giảm mạnh nhất=", "|Largest decrease day="))
					.append(down == null ? none : day(down.getKey()) + " (" + points(down.getValue(), lang) + ")")
					.append('\n');
		}

		if (kvhSeries.isEmpty()) {
			sb.append(t(lang, "KVH (toàn nhà máy): không có Ratio", "KVH (whole plant): no ratio")).append('\n');
		} else {
			sb.append(t(lang, "KVH (toàn nhà máy) Ratio đầu tháng=", "KVH (whole plant) ratio at month start="))
					.append(dayValue(kvhSeries.get(0), lang))
					.append(t(lang, "|Ratio mới nhất=", "|Latest ratio="))
					.append(dayValue(kvhSeries.get(kvhSeries.size() - 1), lang))
					.append('\n');
		}
	}

	// Ngày Ratio đổi dấu so với ngày có số liền trước (bỏ qua 0): "08 (+1.2% -> -0.5%)"
	private static String signChanges(List<Map.Entry<LocalDate, Double>> series, String lang) {

		List<String> result = new ArrayList<>();
		Map.Entry<LocalDate, Double> prev = null;

		for (Map.Entry<LocalDate, Double> e : series) {
			if (e.getValue() == 0) {
				continue;
			}
			if (prev != null && Math.signum(prev.getValue()) != Math.signum(e.getValue())) {
				result.add(day(e.getKey()) + " (" + pct(prev.getValue()) + " -> " + pct(e.getValue()) + ")");
			}
			prev = e;
		}

		return result.isEmpty() ? t(lang, "không có", "none") : String.join(", ", result);
	}

	private static void appendNotes(StringBuilder sb, String lang) {
		sb.append(t(lang,
				"Ghi chú: từ hôm nay trở đi Fluct dùng Ratio của hôm qua; doanh số và tồn kho chi tiết chưa có dữ liệu.",
				"Note: from today onward Fluct uses yesterday's ratio; detailed sales and inventory are not available."));
	}

	private static String dayValue(Map.Entry<LocalDate, Double> e, String lang) {
		return pct(e.getValue()) + t(lang, " (ngày ", " (day ") + day(e.getKey()) + ")";
	}

	private static String day(LocalDate date) {
		return String.format("%02d", date.getDayOfMonth());
	}

	// Chênh Ratio (0.012) -> "+1.2 điểm %"
	private static String points(double delta, String lang) {
		return String.format(Locale.ROOT, "%+.1f", delta * 100) + t(lang, " điểm %", " pp");
	}

	// a − b (Ratio thập phân) tính từ Ratio % đã làm tròn 1 chữ số như cột "Ratio %",
	// trả lại dạng thập phân để points() hiển thị
	private static double pointsDiff(double a, double b) {
		return CmText.r1(CmText.r1(a * 100) - CmText.r1(b * 100)) / 100;
	}
}
