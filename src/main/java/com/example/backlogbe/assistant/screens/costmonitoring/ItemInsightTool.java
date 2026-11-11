package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.tool.AssistantTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// TOOL get_item_insight(div, cost, mode, code?)
//
// Chi tiết vật tư từ dòng xuất kho của 1 khung T&SL / RM (tháng đang xem; nền so sánh = 3 tháng trước):
//   mode=most_issued   : vật tư xuất nhiều lần nhất (số lần, số ngày, SL, K$, đơn giá TB)
//   mode=item          : 1 vật tư (code bắt buộc): ngày xuất nhiều nhất, thứ hay xuất, các tháng trước, đơn giá theo tháng
//   mode=price_changes : vật tư đổi đơn giá ≥ 10% so với TB 3 tháng trước, xếp theo K$ ảnh hưởng
//   mode=anomalies     : tăng giá đột ngột, xuất tăng vọt, mã mới, dòng xuất lớn đơn lẻ
// Dùng chung phần tính / định dạng với câu trả lời template CostMonitoringItemAnswers (cùng số).
// =========================================================
@Component
@RequiredArgsConstructor
public class ItemInsightTool implements AssistantTool {

	private static final List<String> COSTS = List.of("T&SL", "RM");
	private static final List<String> MODES = List.of("most_issued", "item", "price_changes", "anomalies");

	private final CostMonitoringItemAnswers items;

	@Override
	public String name() {
		return "get_item_insight";
	}

	@Override
	public String description() {
		return "Phân tích chi tiết vật tư của 1 khung T&SL hoặc RM từ dòng xuất kho (tháng đang xem, so với 3 tháng trước): "
				+ "most_issued = vật tư xuất / mua nhiều lần nhất; item = 1 mã vật tư (xuất ngày nào, đơn giá theo tháng); "
				+ "price_changes = vật tư đổi đơn giá; anomalies = vật tư bất thường. Không áp dụng cho OT.";
	}

	@Override
	public Map<String, Object> parameters() {
		return CostMonitoringToolArgs.divCostSchema(
				COSTS,
				Map.of(
						"mode", Map.of("type", "string", "enum", MODES, "description", "Loại phân tích"),
						"code", Map.of("type", "string", "description", "Mã vật tư (vd T0026400), bắt buộc khi mode=item")
				)
		);
	}

	@Override
	public String execute(Map<String, Object> args, Map<String, String> screenParams, String lang) {

		String div = CostMonitoringToolArgs.div(args, screenParams);
		String cost = CostMonitoringToolArgs.cost(args, COSTS, screenParams);
		Object modeArg = args.get("mode");
		String mode = modeArg == null ? "most_issued" : modeArg.toString().trim().toLowerCase(Locale.ROOT);
		if (!MODES.contains(mode)) {
			throw new IllegalArgumentException("arg=mode|allowed=" + String.join(",", MODES));
		}

		List<CostMonitoringFocus> frames = List.of(new CostMonitoringFocus(div, cost));
		String result;

		switch (mode) {
			case "item" -> {
				Object code = args.get("code");
				if (code == null || code.toString().isBlank()) {
					throw new IllegalArgumentException("arg=code|required for mode=item");
				}
				CostMonitoringItemAnswers.Found found = items.resolve(code.toString(), frames, screenParams, false);
				result = found == null ? null
						: items.itemWhen(found, screenParams, lang) + "\n\n" + items.itemPrice(found, screenParams, lang);
			}
			case "price_changes" -> result = items.priceChanges(frames, screenParams, lang);
			case "anomalies" -> result = items.anomalies(frames, screenParams, lang);
			default -> result = items.mostIssued(frames, screenParams, lang);
		}

		return result != null ? result
				: div + " " + cost + "|" + t(lang, "Không có dữ liệu vật tư phù hợp", "No matching item data");
	}
}
