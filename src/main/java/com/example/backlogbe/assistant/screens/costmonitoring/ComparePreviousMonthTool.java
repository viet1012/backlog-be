package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.tool.AssistantTool;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;
import static com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshot.fluct;
import static com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshot.fmt;

// =========================================================
// TOOL compare_previous_month(div, cost)
//
// Tháng trước (so với tháng đang xem): FC, Fluct, Act cuối tháng,
// Act cùng kỳ (ngày D, không quá số ngày tháng trước).
// Kèm số tháng này để model so sánh.
// =========================================================
@Component
@RequiredArgsConstructor
public class ComparePreviousMonthTool implements AssistantTool {

	private final CostMonitoringSnapshotLoader loader;

	@Override
	public String name() {
		return "compare_previous_month";
	}

	@Override
	public String description() {
		return "So sánh 1 khung với tháng trước: FC TGT, Fluct, Actual cuối tháng trước và Actual cùng kỳ "
				+ "(cùng ngày D) tháng trước, kèm số tháng này. Dùng khi hỏi so với tháng trước.";
	}

	@Override
	public Map<String, Object> parameters() {
		return CostMonitoringToolArgs.divCostSchema(CostMonitoringSnapshot.COSTS, Map.of());
	}

	@Override
	public String execute(Map<String, Object> args, Map<String, String> screenParams, String lang) {

		String div = CostMonitoringToolArgs.div(args, screenParams);
		String cost = CostMonitoringToolArgs.cost(args, CostMonitoringSnapshot.COSTS, screenParams);

		CostMonitoringSnapshot current = loader.load(screenParams);
		// PL TGT không dùng ở đây -> tháng trước để SP tự chọn tuần Simu
		CostMonitoringSnapshot previous = loader.load(current.month().minusMonths(1).toString(), null);

		CostChartDto cur = current.find(div, cost);
		CostChartDto prev = previous.find(div, cost);

		// Ngày có actual mới nhất của CHÍNH khung này (RM chậm hơn T&SL 1 ngày) – audit 2026-11-10: MOLD RM lấy ngày D
		// của T&SL nên "Actual đến ngày D" trống
		int last = cur != null ? current.lastActualDay(cur) : 0;
		int d = last > 0 ? Math.min(last, current.d()) : current.d();
		int prevDay = Math.min(d, previous.n());

		StringBuilder sb = new StringBuilder();

		String noData = t(lang, "Không có dữ liệu", "No data");

		sb.append(div).append(' ').append(cost).append(t(lang, "|Đơn vị=K$", "|Unit=K$")).append('\n');

		// Tháng đang xem
		sb.append(t(lang, "[Tháng này] ", "[This month] ")).append(current.month())
				.append(t(lang, "|Đến ngày (D)=", "|Up to day (D)=")).append(d)
				.append(t(lang, "|Số ngày trong tháng (N)=", "|Days in month (N)=")).append(current.n());
		if (cur == null) {
			sb.append('|').append(noData).append('\n');
		} else {
			sb.append("|FC=").append(fmt(cur.fcTgt()))
					.append("|Fluct=").append(fmt(fluct(cur)))
					.append(t(lang, "|Actual đến ngày D=", "|Actual up to day D=")).append(fmt(current.actAt(cur, d)))
					.append('\n');
		}

		// Tháng trước: Actual cả tháng và Actual cùng kỳ (đến cùng ngày)
		sb.append(t(lang, "[Tháng trước] ", "[Previous month] ")).append(previous.month())
				.append(t(lang, "|Số ngày trong tháng=", "|Days in month=")).append(previous.n());
		if (prev == null) {
			sb.append('|').append(noData);
		} else {
			sb.append("|FC=").append(fmt(prev.fcTgt()))
					.append("|Fluct=").append(fmt(fluct(prev)))
					.append(t(lang, "|Actual cả tháng=", "|Actual full month=")).append(fmt(prev.actMtdLatest()))
					.append(t(lang, "|Actual cùng kỳ (đến ngày ", "|Actual same period (up to day "))
					.append(prevDay).append(")=")
					.append(fmt(previous.actAt(prev, prevDay)));
		}

		// Chênh tính sẵn (từ số đã làm tròn 1 chữ số) – audit 2026-11-10: model tự trừ FC, không nêu chênh cùng kỳ
		if (cur != null && prev != null) {
			Double curAct = current.actAt(cur, d);
			Double prevAct = previous.actAt(prev, prevDay);
			Double actDiff = CmText.subR1(curAct, prevAct);
			Double prevActR = CmText.r1(prevAct);
			Double actPct = actDiff == null || prevActR == null || prevActR == 0 ? null : actDiff / prevActR * 100;

			sb.append('\n').append(t(lang, "[Chênh tháng này − tháng trước]", "[This month − previous month]"))
					.append("|FC=").append(CmText.signedK(CmText.subR1(cur.fcTgt(), prev.fcTgt())))
					.append("|Fluct=").append(CmText.signedK(CmText.subR1(fluct(cur), fluct(prev))))
					.append(t(lang, "|Actual cùng kỳ=", "|Actual same period=")).append(CmText.signedK(actDiff))
					.append(actPct == null ? "" : " (" + CmText.signedPct1(actPct) + ")")
					.append(t(lang, "|Kết luận cùng kỳ=", "|Same-period conclusion="))
					.append(actDiff == null ? ""
							: actDiff > 0 ? t(lang, "tháng này chi nhiều hơn", "spending higher this month")
							: actDiff < 0 ? t(lang, "tháng này chi ít hơn", "spending lower this month")
							: t(lang, "bằng nhau", "equal"));
		}

		return sb.toString().trim();
	}
}
