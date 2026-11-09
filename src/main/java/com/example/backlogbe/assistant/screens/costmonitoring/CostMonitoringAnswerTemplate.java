package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringAnswerGuide.Frame;
import com.example.backlogbe.assistant.screens.costmonitoring.ExplainVariancePrefetchRule.Intent;
import com.example.backlogbe.assistant.screens.costmonitoring.ExplainVariancePrefetchRule.Plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;
import static com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringAnswerGuide.positive;

// =========================================================
// CÂU TRẢ LỜI TEMPLATE COST MONITORING (AnswerGuide.directAnswer – KHÔNG gọi LLM)
//
// Lý do: gpt-oss-20b (Reasoning: low) vẫn trả lời "không có bất thường" dù khối KẾT LUẬN ghi 4 khung dự báo vượt.
// Với intent có kết luận tính sẵn, code render luôn câu trả lời từ CostMonitoringAssessment (cùng số / thứ tự với
// khối KẾT LUẬN, cùng định nghĩa "dự báo vượt" với /chart và /suggestions).
//
// Intent (ExplainVariancePrefetchRule.plan; điều kiện dùng template: CostMonitoringAnswerGuide.templateEligible):
//   OVERVIEW, FORECAST_ALL : câu mở đầu đếm số khung (openingSentence) + mỗi nhóm có khung: tiêu đề đậm,
//                            mỗi khung 1 dòng; nhóm rỗng bỏ; không khung nào -> 1 câu khẳng định.
//   RANKING                : câu đầu = khung đứng đầu theo % Fluct (+ khung vượt nhiều nhất theo K$ nếu khác),
//                            danh sách đánh số, nhóm "đã vượt thực tế".
//   FORECAST_ONE           : câu đầu = kết luận từng khung (vượt Fluct / vượt FC / trong cả 2), rồi mỗi khung 1 dòng
//                            nêu cả Fluct và FC.
//   "_current_rate"        : như trên nhưng theo tốc độ chi hiện tại.
// Tháng đã kết thúc: không có dự báo; nói theo kết quả tháng (Actual so với Fluct / FC).
// Cuối câu trả lời: 1 dòng nghiêng "Số liệu tháng …, actual đến ngày …".
// Gợi ý tiếp theo (suggestions): "Vì sao X vượt target?" / "Vì sao X dự báo vượt?" cho khung nổi bật,
//   "Cứ chi như vậy…" (dự báo 1 khung), "Vật tư nào chi nhiều nhất ở X?"; tối đa 3.
// =========================================================
final class CostMonitoringAnswerTemplate {

	private static final int MAX_SUGGESTIONS = 3;

	private CostMonitoringAnswerTemplate() {
	}

	// frames: đã sắp xếp (CostMonitoringAnswerGuide.frames); rỗng -> null (trả lời qua LLM)
	static String render(Plan plan, CostMonitoringSnapshot snapshot, List<Frame> frames, String lang) {

		if (frames == null || frames.isEmpty()) {
			return null;
		}

		boolean closed = snapshot.monthClosed();
		boolean currentRate = plan.atCurrentRate() && !closed;

		StringBuilder sb = new StringBuilder();

		if (plan.intent() == Intent.FORECAST_ONE) {
			one(sb, frames, closed, currentRate, lang);
		} else if (plan.intent() == Intent.RANKING) {
			ranking(sb, frames, closed, lang);
		} else {
			groups(sb, frames, closed, currentRate, lang);
		}

		sb.append('\n').append(footer(snapshot, frames, closed, currentRate, lang));

		return sb.toString().trim();
	}


	// =========================================================
	// TỔNG QUAN / DỰ BÁO CHUNG
	// =========================================================

	private static void groups(StringBuilder sb, List<Frame> frames, boolean closed, boolean currentRate, String lang) {

		sb.append(CostMonitoringAnswerGuide.openingSentence(frames, closed, currentRate, lang)).append('\n');

		if (!closed) {
			if (currentRate) {
				CostMonitoringAnswerGuide.appendProjectedGroups(sb, frames, lang, true);
			} else {
				CostMonitoringAnswerGuide.appendEstimateGroups(sb, frames, lang, true);
			}
		}

		CostMonitoringAnswerGuide.appendAlreadyOver(sb, frames, closed, lang, true);
	}


	// =========================================================
	// XẾP HẠNG
	// =========================================================

	private static void ranking(StringBuilder sb, List<Frame> frames, boolean closed, String lang) {

		Frame top = frames.get(0);

		if (closed) {
			sb.append(top.a().pct() == null
					? t(lang, "Tháng đã kết thúc; chưa đủ dữ liệu để xếp hạng.", "Month closed; not enough data to rank.")
					: t(lang, "Tháng đã kết thúc. Đứng đầu theo Actual / Fluct: **", "Month closed. Top by Actual / Fluct: **")
					+ top.name() + "** (" + t(lang, "Actual bằng ", "Actual at ") + CmText.pct1(top.a().pct())
					+ t(lang, " Fluct; ", " of Fluct; ") + CmText.monthResult(top.a(), lang) + ").")
					.append('\n');

			CostMonitoringAnswerGuide.appendClosedRanking(sb, frames, lang, true);
			CostMonitoringAnswerGuide.appendAlreadyOver(sb, frames, true, lang, true);
			return;
		}

		CostMonitoringAssessment a = top.a();

		if (a.estimatePctOfFluct() == null) {
			sb.append(t(lang, "Chưa đủ dữ liệu dự báo để xếp hạng.", "Not enough forecast data to rank."));
		} else {
			sb.append(t(lang, "Đứng đầu theo dự báo cuối tháng: **", "Top by month-end forecast: **"))
					.append(top.name()).append("**, ")
					.append(t(lang, "bằng ", "at ")).append(CmText.pct1(a.estimatePctOfFluct()))
					.append(t(lang, " Fluct (", " of Fluct ("))
					.append(CmText.versus("Fluct", a.estimateOver(), a.estimateOverPct(), lang)).append(").");
		}

		Optional<Frame> byAmount = CostMonitoringAnswerGuide.topByAmount(frames);

		if (byAmount.isPresent() && byAmount.get() != top) {
			Frame f = byAmount.get();
			sb.append(t(lang, " Vượt Fluct nhiều nhất theo K$: **", " Largest excess over Fluct in K$: **"))
					.append(f.name()).append("** ").append(CmText.signedK(f.a().estimateOver()))
					.append(" (").append(CmText.signedPct1(f.a().estimateOverPct())).append(").");
		}

		sb.append('\n');

		CostMonitoringAnswerGuide.appendRanking(sb, frames, lang, true);
		CostMonitoringAnswerGuide.appendAlreadyOver(sb, frames, false, lang, true);
	}


	// =========================================================
	// DỰ BÁO 1 BỘ PHẬN (1 hoặc vài loại chi phí)
	// =========================================================

	private static void one(StringBuilder sb, List<Frame> frames, boolean closed, boolean currentRate, String lang) {

		if (closed) {
			sb.append(t(lang, "Tháng đã kết thúc (không còn dự báo), kết quả tháng:",
					"Month closed (no forecast), month result:")).append("\n\n");

			for (Frame f : frames) {
				CostMonitoringAssessment a = f.a();
				sb.append("- **").append(f.name()).append("**: Actual ").append(CmText.k1(a.act()))
						.append("; ").append(CmText.versus("Fluct", CmText.subR1(a.act(), a.fluct()), null, lang))
						.append("; ").append(CmText.versus("FC", CmText.subR1(a.act(), a.fc()), null, lang))
						.append('\n');
			}
			return;
		}

		List<String> verdicts = new ArrayList<>();

		for (Frame f : frames) {
			verdicts.add("**" + f.name() + "** " + verdict(f.a(), currentRate, lang));
		}

		sb.append(currentRate ? t(lang, "Theo tốc độ chi hiện tại, ", "At the current spending rate, ") : "")
				.append(String.join("; ", verdicts)).append(".\n\n");

		CostMonitoringAnswerGuide.appendOne(sb, frames, currentRate, lang);
	}

	// Kết luận 1 khung (nêu cả Fluct và FC), số đã làm tròn 1 chữ số
	private static String verdict(CostMonitoringAssessment a, boolean currentRate, String lang) {

		if (currentRate) {
			if (a.projectedEom() == null) {
				return t(lang, "chưa có dự báo", "has no projection");
			}
			if (positive(a.projectedOver())) {
				return t(lang, "dự báo cuối tháng vượt Fluct", "is projected above Fluct at month-end")
						+ (positive(a.projectedOverFc()) ? t(lang, " và vượt FC", " and above FC") : "");
			}
			return positive(a.projectedOverFc())
					? t(lang, "dự báo cuối tháng vượt FC nhưng vẫn trong Fluct",
					"is projected above FC but within Fluct at month-end")
					: t(lang, "dự báo cuối tháng trong Fluct và FC", "is projected within Fluct and FC at month-end");
		}

		if (a.alreadyOver()) {
			return t(lang, "Actual đã vượt Fluct", "Actual is already above Fluct");
		}
		if (a.estimateEom() == null) {
			return t(lang, "chưa có dự báo", "has no forecast");
		}
		if (a.estimateWillExceed()) {
			return t(lang, "dự báo cuối tháng vượt Fluct", "is forecast above Fluct at month-end")
					+ (positive(a.estimateOverFc()) ? t(lang, " và vượt FC", " and above FC") : "");
		}

		return positive(a.estimateOverFc())
				? t(lang, "dự báo cuối tháng vượt FC nhưng vẫn trong Fluct",
				"is forecast above FC but within Fluct at month-end")
				: t(lang, "dự báo cuối tháng trong Fluct và FC", "is forecast within Fluct and FC at month-end");
	}

	// "_Số liệu tháng 2026-11, actual đến ngày 7; <cơ sở dự báo>._" – cơ sở theo estimateBase thực tế của các khung:
	//   mọi khung theo PL -> "dự báo theo PL"; mọi khung theo nhịp Fluct -> "chưa có PL nên dự báo theo nhịp Fluct";
	//   trộn -> "dự báo theo PL; khung chưa có PL theo nhịp Fluct"; câu "cứ chi như vậy" -> "dự báo theo tốc độ chi hiện tại"
	private static String footer(CostMonitoringSnapshot snapshot, List<Frame> frames, boolean closed, boolean currentRate,
								 String lang) {

		if (closed) {
			return "_" + t(lang, "Số liệu tháng ", "Data for month ") + snapshot.month()
					+ t(lang, " (đã kết thúc).", " (closed).") + "_";
		}

		String base;

		if (currentRate) {
			base = t(lang, "dự báo theo tốc độ chi hiện tại", "projection at the current spending rate");
		} else {
			boolean anyPl = frames.stream().anyMatch(f -> "PL".equals(f.a().estimateBase()));
			boolean anyFluct = frames.stream().anyMatch(f -> "Fluct".equals(f.a().estimateBase()));

			if (anyFluct && !anyPl) {
				base = t(lang, "chưa có PL nên dự báo theo nhịp Fluct", "no PL yet, so the forecast follows the Fluct pace");
			} else if (anyFluct) {
				base = t(lang, "dự báo theo PL; khung chưa có PL theo nhịp Fluct",
						"forecast based on PL; frames without PL follow the Fluct pace");
			} else {
				base = t(lang, "dự báo theo PL", "forecast based on PL");
			}
		}

		return "_" + t(lang, "Số liệu tháng ", "Data for month ") + snapshot.month()
				+ t(lang, ", actual đến ngày ", ", actual up to day ") + snapshot.d() + "; " + base + "._";
	}


	// =========================================================
	// GỢI Ý TIẾP THEO
	// =========================================================

	static List<String> suggestions(Plan plan, CostMonitoringSnapshot snapshot, List<Frame> frames, String lang) {

		if (frames == null || frames.isEmpty()) {
			return List.of();
		}

		boolean closed = snapshot.monthClosed();
		boolean currentRate = plan.atCurrentRate() && !closed;

		Set<String> out = new LinkedHashSet<>();
		Frame firstFlagged = null;

		// Khung nổi bật (đã vượt / dự báo vượt), theo thứ tự hiển thị, tối đa 2 câu "vì sao"
		for (Frame f : frames) {

			CostMonitoringAssessment a = f.a();
			String text = null;

			if (a.alreadyOver()) {
				text = t(lang, "Vì sao " + f.name() + " vượt target?", "Why is " + f.name() + " over target?");
			} else if (!closed && (currentRate ? positive(a.projectedOver()) : a.estimateWillExceed())) {
				text = t(lang, "Vì sao " + f.name() + " dự báo vượt?", "Why is " + f.name() + " forecast to exceed?");
			}

			if (text != null) {
				out.add(text);
				if (firstFlagged == null) {
					firstFlagged = f;
				}
				if (out.size() >= 2) {
					break;
				}
			}
		}

		Frame focus = firstFlagged != null ? firstFlagged : plan.intent() == Intent.FORECAST_ONE ? frames.get(0) : null;

		// Dự báo 1 bộ phận (chính) -> hỏi tiếp theo tốc độ chi hiện tại
		if (!closed && !currentRate && plan.intent() == Intent.FORECAST_ONE && focus != null) {
			out.add(t(lang, "Cứ chi như vậy thì " + focus.name() + " có vượt FC không?",
					"If spending continues like this, will " + focus.name() + " exceed FC?"));
		}

		if (focus != null) {
			out.add(t(lang, "Vật tư nào chi nhiều nhất ở " + focus.name() + "?",
					"Which items cost the most in " + focus.name() + "?"));
		}

		// Không khung nào nổi bật (tổng quan / xếp hạng)
		if (out.isEmpty()) {
			if (!closed && !currentRate) {
				out.add(t(lang, "Cứ chi như vậy thì tháng này có vượt không?",
						"If spending continues like this, will this month exceed?"));
			}
			String top = frames.get(0).name();
			out.add(t(lang, top + " so với tháng trước thế nào?", "How does " + top + " compare to last month?"));
		}

		return out.stream().limit(MAX_SUGGESTIONS).toList();
	}
}
