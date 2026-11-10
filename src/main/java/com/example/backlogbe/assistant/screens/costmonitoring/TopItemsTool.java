package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.tool.AssistantTool;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopCostCenterDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopItemDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopMachineDto;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostTopItemsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// TOOL get_top_items(div, cost, limit)
//
// cost ∈ T&SL, RM. Từ CostTool / CostRm của tháng đang xem, 3 phần (mỗi phần ≤ limit dòng):
//   Top vật tư     : Code|Text|Category|tổng Qty + QtyUnit|K$   (tổng âm -> "(điều chỉnh)")
//   Top cost center: PLcostcentername (PLcostcenter)|K$
//   Top máy        : Machine|K$   (bỏ Machine rỗng)
// Mỗi dòng có "% của Actual" (tỷ trọng); cuối mỗi phần: "Tổng top N …" = K$ cộng sẵn + % của Actual
//   (model không tự cộng / tự tính %).
// Dòng cuối: tổng K$ mọi dòng thỏa bộ lọc (gồm dòng âm) + ghi chú chưa gồm phân bổ.
// K$ = Amount / 1000, làm tròn 2 chữ số.
// Khoảng ngày: đầu tháng -> mốc actual (T&SL: hôm qua, RM: hôm kia), không quá cuối tháng.
// Chỉ cost center trực tiếp của bộ phận (không gồm chi phí chung phân bổ F2/HT2/HT-G).
// =========================================================
@Component
@RequiredArgsConstructor
public class TopItemsTool implements AssistantTool {

	private static final List<String> COSTS = List.of("T&SL", "RM");
	private static final int DEFAULT_LIMIT = 5;
	private static final int MAX_LIMIT = 10;

	private final CostMonitoringSnapshotLoader loader;
	private final CostTopItemsRepository repository;

	@Override
	public String name() {
		return "get_top_items";
	}

	@Override
	public String description() {
		return "Lấy top vật tư (mã, tên, Category, số lượng, K$), top cost center và top máy theo chi phí "
				+ "của 1 khung T&SL hoặc RM trong tháng đang xem, kèm tổng K$. Dùng khi hỏi nguyên nhân vượt, "
				+ "vật tư / loại vật tư / cost center / máy nào chi nhiều. Không áp dụng cho OT.";
	}

	@Override
	public Map<String, Object> parameters() {
		return CostMonitoringToolArgs.divCostSchema(
				COSTS,
				Map.of("limit", Map.of(
						"type", "integer",
						"minimum", 1,
						"maximum", MAX_LIMIT,
						"description", "Số dòng mỗi danh sách (mặc định " + DEFAULT_LIMIT + ", tối đa " + MAX_LIMIT + ")"
				))
		);
	}

	@Override
	public String execute(Map<String, Object> args, Map<String, String> screenParams, String lang) {

		String div = CostMonitoringToolArgs.div(args, screenParams);
		String cost = CostMonitoringToolArgs.cost(args, COSTS, screenParams);
		int limit = CostMonitoringToolArgs.intValue(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

		YearMonth month = loader.month(screenParams);
		LocalDate from = month.atDay(1);

		// Mốc actual giống SP: T&SL đến hôm qua, RM đến hôm kia
		LocalDate cutoff = loader.today().minusDays("RM".equals(cost) ? 2 : 1);
		LocalDate to = cutoff.isBefore(month.atEndOfMonth()) ? cutoff : month.atEndOfMonth();

		String header = div + " " + cost + t(lang, "|Tháng=", "|Month=") + month;

		if (to.isBefore(from)) {
			return header + "|" + t(lang, "Chưa có actual", "No actual yet");
		}

		List<String> costCenters = CostMonitoringSnapshot.DIV_COST_CENTERS.get(div);

		List<CostTopItemDto> items = repository.findTopItems(cost, from, to, costCenters, limit);
		List<CostTopCostCenterDto> centers = repository.findTopCostCenters(cost, from, to, costCenters, limit);
		List<CostTopMachineDto> machines = repository.findTopMachines(cost, from, to, costCenters, limit);
		Double total = repository.findTotal(cost, from, to, costCenters);

		// Act của khung trên chart, để tính tỷ trọng từng dòng (pct_act = K$ / Act × 100)
		CostChartDto chart = loader.load(screenParams).find(div, cost);
		Double act = chart != null ? chart.actMtdLatest() : null;

		StringBuilder sb = new StringBuilder();

		sb.append(header)
				.append(t(lang, "|Từ ngày=", "|From=")).append(from)
				.append(t(lang, "|Đến ngày=", "|To=")).append(to)
				.append("|Actual=").append(k(act))
				.append(t(lang, "|Đơn vị=K$", "|Unit=K$"))
				.append('\n');

		sb.append(t(lang,
				"[Top vật tư] cột=Vật tư (Code)|Category|Số lượng|K$|% của Actual",
				"[Top items] cols=Item (Code)|Category|Qty|K$|% of Actual")).append('\n');
		if (items.isEmpty()) {
			sb.append(CmText.none(lang)).append('\n');
		}
		for (CostTopItemDto item : items) {
			// "Tên (Code)", tên từ [Text] cắt tối đa 40 ký tự
			sb.append(CmText.itemLabel(item.code(), item.name(), lang))
					.append('|').append(clean(item.category()))
					.append('|').append(qty(item.qty(), item.qtyUnit()))
					.append('|').append(k(item.amountK()));
			if (item.amountK() != null && item.amountK() < 0) {
				sb.append(t(lang, " (điều chỉnh)", " (adjustment)"));
			}
			sb.append('|').append(pctAct(item.amountK(), act)).append('\n');
		}
		appendTopTotal(sb, items.stream().map(CostTopItemDto::amountK).toList(),
				t(lang, "vật tư", "items"), act, lang);

		sb.append(t(lang,
				"[Top cost center] cột=PLcostcentername (PLcostcenter)|K$|% của Actual",
				"[Top cost centers] cols=PLcostcentername (PLcostcenter)|K$|% of Actual")).append('\n');
		if (centers.isEmpty()) {
			sb.append(CmText.none(lang)).append('\n');
		}
		for (CostTopCostCenterDto center : centers) {
			sb.append(clean(center.name()));
			if (center.code() != null && !center.code().isBlank()) {
				sb.append(" (").append(clean(center.code())).append(')');
			}
			sb.append('|').append(k(center.amountK()))
					.append('|').append(pctAct(center.amountK(), act))
					.append('\n');
		}
		appendTopTotal(sb, centers.stream().map(CostTopCostCenterDto::amountK).toList(),
				t(lang, "cost center", "cost centers"), act, lang);

		sb.append(t(lang,
				"[Top máy] cột=Machine|K$|% của Actual",
				"[Top machines] cols=Machine|K$|% of Actual")).append('\n');
		if (machines.isEmpty()) {
			sb.append(CmText.none(lang)).append('\n');
		}
		for (CostTopMachineDto machine : machines) {
			sb.append(clean(machine.machine()))
					.append('|').append(k(machine.amountK()))
					.append('|').append(pctAct(machine.amountK(), act))
					.append('\n');
		}
		appendTopTotal(sb, machines.stream().map(CostTopMachineDto::amountK).toList(),
				t(lang, "máy", "machines"), act, lang);

		// Tổng mọi dòng thỏa bộ lọc (gồm dòng âm), chỉ cost center trực tiếp
		sb.append(t(lang, "Tổng chi phí trực tiếp=", "Total direct cost=")).append(k(total))
				.append(t(lang, "|Cost center trực tiếp=", "|Direct cost centers=")).append(String.join(",", costCenters))
				.append(t(lang,
						"|Chưa gồm phân bổ F2/HT2/HT-G",
						"|Excludes F2/HT2/HT-G allocation"));

		return sb.toString();
	}


	// =========================================================
	// FORMAT
	// =========================================================

	// "Tổng top 5 vật tư=12.34 K$|% của Actual=45.6%": cộng các K$ đã làm tròn 2 chữ số đúng như từng dòng
	// (khớp phép cộng người đọc tự làm), % tính từ tổng đó. Danh sách rỗng -> không ghi.
	private static void appendTopTotal(StringBuilder sb, List<Double> amounts, String label, Double act, String lang) {

		double sum = 0;
		int count = 0;

		for (Double amount : amounts) {
			Double rounded = CmText.r2(amount);
			if (rounded != null) {
				sum += rounded;
				count++;
			}
		}

		if (count == 0) {
			return;
		}

		sb.append(t(lang, "Tổng top ", "Total of top ")).append(count).append(' ').append(label)
				.append('=').append(k(sum))
				.append(t(lang, "|% của Actual=", "|% of Actual=")).append(pctAct(sum, act))
				.append(t(lang,
						" (dùng đúng số này, không tự cộng)",
						" (use this number, do not add up yourself)"))
				.append('\n');
	}

	// amount / Act × 100 (%); thiếu số -> trống
	private static String pctAct(Double amountK, Double act) {
		return amountK == null ? "" : ExplainVarianceTool.pctOfAct(amountK, act);
	}

	// K$ làm tròn 2 chữ số
	private static String k(Double value) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}

		return String.format(Locale.ROOT, "%.2f", value);
	}

	// "12.5 PC": bỏ số 0 thừa, tối đa 2 chữ số thập phân
	private static String qty(Double value, String unit) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}

		String number = BigDecimal.valueOf(value)
				.setScale(2, RoundingMode.HALF_UP)
				.stripTrailingZeros()
				.toPlainString();

		return unit == null || unit.isBlank() ? number : number + " " + clean(unit);
	}

	// "|" / xuống dòng trong dữ liệu sẽ làm lệch cột
	private static String clean(String value) {
		return CmText.clean(value);
	}
}
