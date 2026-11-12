package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.screen.AnswerGuide;
import com.example.backlogbe.assistant.screens.costmonitoring.ExplainVariancePrefetchRule.Plan;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartForecastDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// HƯỚNG DẪN TRẢ LỜI COST MONITORING (ScreenModule.answerGuide)
//
// Lý do: gpt-oss-20b (Reasoning: low) bỏ qua kết quả pre-fetch nằm giữa context dài. Code tự kết luận, đặt sát câu hỏi.
//
// "Dự báo vượt" thống nhất với /chart và /suggestions (CostMonitoringAssessment.mainStatus):
//   CHÍNH  = PL Estimate (estMonthEnd; không PL thì Act + Fluct / N × (N − D))  -> estimate*
//   PHỤ    = tốc độ chi hiện tại (projected*), chỉ cho câu "cứ chi như vậy / giữ tốc độ" (Plan.atCurrentRate)
//   Khung 2 cách trái chiều (projectionsDisagree): thêm ghi chú "theo tốc độ chi hiện tại thì …".
//
// Loại câu hỏi (ExplainVariancePrefetchRule.plan):
//   OVERVIEW, FORECAST_ALL (không "cứ chi"): nhóm theo trạng thái chính, chỉ ghi nhóm CÓ khung:
//     (a) Dự báo vượt Fluct (WILL_EXCEED, chưa vượt thực tế), (a2) dự báo vượt FC nhưng trong Fluct,
//     (b) Đã vượt thực tế (OVER), (r) chỉ vượt theo tốc độ chi hiện tại (tham khảo) – khung trái chiều không thuộc (a)/(b)
//     mọi nhóm rỗng -> 1 dòng khẳng định.
//   FORECAST_ALL "cứ chi như vậy": nhóm theo projected (như trước), ghi rõ "theo tốc độ chi hiện tại".
//   RANKING: xếp hạng theo % Fluct dự báo chính + khung vượt nhiều nhất theo K$ + (b).
//     Hỏi vật tư / máy / cost center / Category -> vẫn cho gọi tool.
//   FORECAST_ONE: 1 dòng mỗi khung được hỏi (chính, hoặc tốc độ chi nếu "cứ chi như vậy"), cấm gọi thêm tool.
//   FRAME / không pre-fetch -> null.
// Tháng đã kết thúc: không có dự báo; (b) theo Kết quả tháng; xếp hạng theo Actual / Fluct.
// toolsSufficient = true: ChatOrchestrator chỉ cấm tool khi pre-fetch thực sự ra kết quả, không lỗi.
//
// CÂU TRẢ LỜI TEMPLATE (directAnswer, CostMonitoringAnswerTemplate – không gọi LLM): OVERVIEW, RANKING (không hỏi chi
// tiết), FORECAST_ALL, FORECAST_ONE (+ "_current_rate"), khi templateEligible: không hỏi nguyên nhân, vật tư / máy /
// cost center / Category, theo ngày, tháng trước, và (FORECAST_ONE) không hỏi kèm khung khác.
// Lý do: dù có khối KẾT LUẬN, gpt-oss-20b (low) vẫn trả lời "không có bất thường" khi có khung dự báo vượt.
// Khối KẾT LUẬN vẫn được tính (dùng khi assistant.template-answers.enabled = false hoặc câu hỏi không đủ điều kiện).
// =========================================================
@lombok.extern.slf4j.Slf4j
@Component
@RequiredArgsConstructor
public class CostMonitoringAnswerGuide {

	// Hỏi chi tiết (vật tư / máy / cost center / Category) -> cần get_top_items, không cấm tool, không template.
	// Không dấu, khớp trên câu đã bỏ dấu (CostMonitoringIntentClassifier.fold)
	private static final List<String> DETAIL_KEYWORDS = List.of(
			"vat tu", "cost center", "category", "may", "item", "machine", "material"
	);

	// "Vì sao" cần giải thích sâu hơn kết luận tính sẵn (Ratio, so cùng kỳ, nhiều khung) -> vẫn qua LLM
	private static final List<String> WHY_LLM_KEYWORDS = List.of(
			"ratio", "fluct bi", "fluct duoc", "target bi", "target duoc", "cung ky", "thang truoc", "khung nao", "bo phan nao",
			"last month", "previous month"
	);

	// So sánh: chênh % Fluct dự báo dưới mức này (điểm %) -> "tương đương"
	private static final double COMPARE_EQUAL_POINTS = 1.0;

	private final CostMonitoringSnapshotLoader loader;
	private final CostMonitoringLookupAnswers lookupAnswers;
	private final CostMonitoringManagerAnswers managerAnswers;
	private final CostMonitoringItemAnswers itemAnswers;
	private final CostMonitoringRangeAnswers rangeAnswers;

	// Câu hỏi về khoảng nhiều tháng (ChatOrchestrator -> answerGuideForRange): chỉ template tính theo cả khoảng
	// (vật tư xuất nhiều lần nhất / 1 vật tư; top vật tư / máy / cost center / Category; tổng hợp theo từng tháng).
	// Ngoại lệ: "N tháng gần đây / qua / trước" không hỏi chi tiết -> multi_month của manager (các tháng đã kết thúc).
	// Không dùng template / khối kết luận của 1 tháng (manager, lookup, tổng quan…) – không khớp -> null (LLM + rangeNote).
	//
	// Giới hạn thời gian (test 0758 ext-244 "từ đầu năm": template đúng nhưng 262–277 s, FE hết chờ ở ~190 s và báo lỗi):
	// quá RANGE_TIMEOUT -> trả lời "đang tổng hợp, hỏi lại sau" (directAnswer, không gọi LLM); phần tính vẫn chạy nền
	// đến hết và nạp cache repository (tháng đã kết thúc giữ 6 giờ) -> hỏi lại trả ngay.
	public AnswerGuide range(String question, Map<String, String> params,
							 com.example.backlogbe.assistant.core.month.QuestionMonthDetector.MonthRange range, String lang) {

		java.util.concurrent.CompletableFuture<AnswerGuide> future =
				java.util.concurrent.CompletableFuture.supplyAsync(() -> rangeNow(question, params, range, lang), rangeExecutor);

		try {
			return future.get(RANGE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			log.warn("Assistant range answer slow, timeout={}s months={}", RANGE_TIMEOUT_SECONDS, range.months());
			return slowRange(question, range, lang);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (java.util.concurrent.ExecutionException e) {
			log.warn("Assistant range answer failed error={}",
					e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName());
			return null;
		}
	}

	private static final long RANGE_TIMEOUT_SECONDS = 45;

	// Luồng riêng cho câu hỏi theo khoảng (daemon; chạy tiếp sau khi request đã trả "đang tổng hợp")
	private final java.util.concurrent.ExecutorService rangeExecutor = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
		Thread th = new Thread(r, "assistant-range");
		th.setDaemon(true);
		return th;
	});

	// Quá thời gian: nói rõ đang tính, hỏi lại sau (gợi ý = đúng câu vừa hỏi để bấm gửi lại)
	private static AnswerGuide slowRange(String question,
										 com.example.backlogbe.assistant.core.month.QuestionMonthDetector.MonthRange range, String lang) {
		String text = "**" + t(lang, "Khoảng ", "Range ") + range.label() + " (" + range.months() + t(lang, " tháng)**", " months)**")
				+ t(lang, " – số liệu nhiều tháng đang được tổng hợp, mất hơn " + RANGE_TIMEOUT_SECONDS
								+ " giây nên chưa trả kịp. Hệ thống vẫn tính tiếp ở nền: bạn hỏi lại câu này sau khoảng 1–2 phút sẽ có kết quả ngay, "
								+ "hoặc thu hẹp khoảng (vd 3 tháng).",
						" – the multi-month data is still being aggregated (over " + RANGE_TIMEOUT_SECONDS
								+ " s). It keeps running in the background: ask again in 1–2 minutes for an instant answer, "
								+ "or narrow the range (e.g. 3 months).");
		return new AnswerGuide(null, false, "range_slow", text, List.of(question),
				"range=" + range.from() + ".." + range.to() + "; kind=range_slow; template=yes");
	}

	private AnswerGuide rangeNow(String question, Map<String, String> params,
								 com.example.backlogbe.assistant.core.month.QuestionMonthDetector.MonthRange range, String lang) {

		AnswerGuide item = itemAnswers.guide(question, params, range, lang);
		if (item != null) {
			return item;
		}

		// "3 tháng gần đây PRESS T&SL có tháng nào vượt?" -> multi_month (tháng đã kết thúc), như trước khi có luồng khoảng
		AnswerGuide multi = managerAnswers.multiMonthGuide(question, params, lang);
		if (multi != null) {
			return multi;
		}

		return rangeAnswers.guide(question, params, range, lang);
	}

	// "3 tháng tới / quý tới / các tháng tới": lõi không nhận khoảng tương lai, câu trả lời là dự báo tháng đang xem ->
	// thêm ghi chú không có dự báo cho các tháng sau (test 0758 ext-255: chỉ trả dự báo tháng 11, không nói gì về 12–01)
	private static final Pattern FUTURE_MONTHS = CostMonitoringQuestion.word(
			"(?:\\d{1,2}|hai|ba|bon|nam|sau|cac|may|nhung) thang (?:toi|sap toi|tiep theo|ke tiep|sau)|quy toi|quy sau|nam sau"
					+ "|next (?:\\d{1,2}|two|three|four|five|six) months|coming months|next quarter|next year");

	public AnswerGuide guide(String question, Map<String, String> params, String lang) {

		AnswerGuide g = guideCurrent(question, params, lang);

		if (g == null || !g.hasDirectAnswer() || !FUTURE_MONTHS.matcher(CostMonitoringIntentClassifier.fold(question)).find()) {
			return g;
		}

		java.time.YearMonth viewed = loader.month(params != null ? params : Map.of());
		String note = t(lang, "_Các tháng sau " + viewed + " chưa có Actual hay dự báo trên màn hình – số trên chỉ là dự báo cuối tháng "
						+ viewed + ". Muốn xem target tháng sau: chọn tháng đó trên màn hình._",
				"_Months after " + viewed + " have no Actual or forecast on screen – the numbers above are the " + viewed
						+ " month-end forecast only. For next month's targets, select that month on the screen._");

		return new AnswerGuide(g.conclusion(), g.toolsSufficient(), g.intent(), g.directAnswer() + "\n\n" + note,
				g.suggestions(), g.intentMatch() + "; futureNote=yes");
	}

	private AnswerGuide guideCurrent(String question, Map<String, String> params, String lang) {

		// Câu hỏi cấp quản lý (tóm tắt, báo cáo, tổng dự kiến, what-if, bộ phận đáng lo, duyệt / xuất file…): template
		// trước mọi luồng khác (test 2026-11-11 nhóm 30–34: qua LLM 12–70 s, nhiều câu sai)
		AnswerGuide manager = managerAnswers.guide(question, params, lang);
		if (manager != null) {
			return manager;
		}

		// Chi tiết vật tư: xuất nhiều lần nhất, xuất vào lúc nào, đơn giá thay đổi, vật tư bất thường (template từ dòng xuất kho)
		AnswerGuide item = itemAnswers.guide(question, params, lang);
		if (item != null) {
			// "Chi tiết những bất thường" (không nêu vật tư): khung nào dự báo vượt trước, rồi vật tư cần kiểm tra
			// (eval #13: chỉ trả danh sách vật tư, thiếu khung dự báo vượt Fluct)
			if (CostMonitoringItemAnswers.ITEM_ANOMALY.equals(item.intent()) && !CostMonitoringItemAnswers.mentionsItem(question)) {
				AnswerGuide merged = withOverview(item, question, params, lang);
				if (merged != null) {
					return merged;
				}
			}
			return item;
		}

		// "Tháng trước có gì bất thường / tình hình tháng trước": template tổng quan của tháng trước
		AnswerGuide previous = previousMonthOverview(question, params, lang);
		if (previous != null) {
			return previous;
		}

		// Câu tra cứu (top vật tư / ngày chi nhiều nhất / ngân sách mỗi ngày / bất thường hôm nay): template trước
		AnswerGuide lookup = lookup(question, params, lang);

		if (lookup == null) {
			return byIntent(question, params, lang);
		}

		if (!CostMonitoringLookupAnswers.ANOMALY_TODAY.equals(lookup.intent())) {
			return lookup;
		}

		// "Hôm nay có gì bất thường": chi ngày gần nhất + template tổng quan tháng (nếu câu hỏi đủ điều kiện)
		AnswerGuide overview = byIntent(question, params, lang);

		if (overview == null || !overview.hasDirectAnswer()) {
			return lookup;
		}

		List<String> suggestions = new ArrayList<>(lookup.suggestions());
		overview.suggestions().stream().filter(s -> !suggestions.contains(s)).forEach(suggestions::add);

		return new AnswerGuide(overview.conclusion(), overview.toolsSufficient(), overview.intent() + "+" + lookup.intent(),
				lookup.directAnswer() + "\n\n" + t(lang, "**Tình hình cả tháng:**", "**Month to date:**") + "\n"
						+ compactOverview(overview.directAnswer(), lang),
				suggestions.stream().limit(3).toList(),
				overview.intentMatch() + "; " + lookup.intentMatch());
	}

	// Tổng quan khung (template OVERVIEW, rút gọn) + câu trả lời chi tiết vật tư; tổng quan không có template -> null
	private AnswerGuide withOverview(AnswerGuide item, String question, Map<String, String> params, String lang) {

		AnswerGuide overview;
		try {
			overview = byIntent(question, params, lang);
		} catch (RuntimeException e) {
			return null;
		}

		if (overview == null || !overview.hasDirectAnswer()) {
			return null;
		}

		List<String> suggestions = new ArrayList<>(overview.suggestions());
		item.suggestions().stream().filter(s -> !suggestions.contains(s)).forEach(suggestions::add);

		return new AnswerGuide(overview.conclusion(), overview.toolsSufficient(), overview.intent() + "+" + item.intent(),
				t(lang, "**Tình hình các khung:**", "**Frames:**") + "\n" + compactOverview(overview.directAnswer(), lang)
						+ "\n\n" + t(lang, "**Vật tư cần kiểm tra:**", "**Items to check:**") + "\n" + item.directAnswer(),
				suggestions.stream().limit(3).toList(),
				overview.intentMatch() + "; " + item.intentMatch());
	}

	// Audit 2026-11-10: "Tháng trước có gì bất thường?" qua LLM gọi tool lung tung, trả "commentary to=functions?".
	// Chỉ khi hỏi tình hình chung của tháng trước (không so sánh với tháng này / cùng kỳ).
	private static final Pattern PREVIOUS_MONTH = CostMonitoringQuestion.word("thang truoc|last month|previous month");
	private static final Pattern PREVIOUS_MONTH_COMPARE = CostMonitoringQuestion.word(
			"so voi|so sanh|cung ky|hon|compare|compared|vs|versus|than|thang nay|this month");

	// "tháng 9", "tháng 09/2026" (test 2026-11-10 ext-17: "Tháng 9 có gì bất thường?" qua LLM 34 s)
	private static final Pattern NAMED_MONTH = Pattern.compile("(?<![\\p{L}\\d])thang (1[0-2]|0?[1-9])(?:\\s*[/-]\\s*(20\\d{2}))?(?!\\d)");

	// ChatOrchestrator gọi khi câu hỏi nhắc 1 tháng khác (không đi qua guide()): chỉ template tổng quan tháng đó
	public AnswerGuide otherMonth(String question, Map<String, String> params, String lang) {
		AnswerGuide availability = dataAvailability(question, params, lang);
		if (availability != null) {
			return availability;
		}
		// Chi tiết vật tư tự dò tháng trong câu ("tháng 9", "T10"…) – test 1643 ext-232 / 233: nhánh tháng khác không
		// gọi itemAnswers nên qua LLM 15–40 s, trả "không có dữ liệu" / "275 đ/ST"
		AnswerGuide item = itemAnswers.guide(question, params, lang);
		if (item != null) {
			return item;
		}
		return previousMonthOverview(question, params, lang);
	}

	// "Tháng 3 năm nay có dữ liệu không?", "Tháng 9 đã có số chưa?" -> trả bằng code từ snapshot tháng đó
	// (test 2026-11-12 ext-254: LLM 53 s bịa "Có, tháng 3/2026 đã có dữ liệu" và trích nhãn khối dữ liệu nội bộ)
	private static final Pattern DATA_ASK = CostMonitoringQuestion.word(
			"co du lieu|co so lieu|co data|co so chua|da co so|du lieu chua|so lieu chua|co so lieu chua|du lieu khong"
					+ "|have data|any data|data available|is there data");

	private AnswerGuide dataAvailability(String question, Map<String, String> params, String lang) {

		String f = CostMonitoringIntentClassifier.fold(question);
		if (!DATA_ASK.matcher(f).find()) {
			return null;
		}

		Map<String, String> safe = params != null ? params : Map.of();
		java.time.YearMonth viewed = loader.month(safe);
		java.time.YearMonth month = CostMonitoringItemAnswers.askedMonth(question, f, viewed);
		if (month == null || month.equals(viewed)) {
			return null;
		}

		String label = "**" + t(lang, "Tháng ", "Month ") + month + "**: ";
		String text;
		boolean hasActual = false;

		if (month.isAfter(java.time.YearMonth.from(loader.today()))) {
			text = label + t(lang, "chưa tới nên chưa có số chi (Actual) – chỉ có target nếu kế hoạch đã nhập.",
					"not reached yet, so no Actual spending – only targets if the plan has been entered.");
		} else {
			CostMonitoringSnapshot s = loader.load(month.toString(), null);
			List<CostChartDto> charts = s.response() == null || s.response().charts() == null ? List.of() : s.response().charts();
			boolean target = charts.stream().anyMatch(c -> c.fcTgt() != null);
			int tsl = 0;
			int rm = 0;
			for (CostChartDto c : charts) {
				if (c.actMtdLatest() == null) {
					continue;
				}
				int day = s.lastActualDay(c);
				if ("T&SL".equals(c.chart())) {
					tsl = Math.max(tsl, day);
				} else if ("RM".equals(c.chart())) {
					rm = Math.max(rm, day);
				}
			}
			hasActual = tsl > 0 || rm > 0;
			if (!hasActual) {
				text = label + t(lang, "**chưa có số chi (Actual)** trên màn hình Cost Monitoring",
						"**no Actual spending** on the Cost Monitoring screen")
						+ (target ? t(lang, "; chỉ có target (FC / Fluct).", "; targets only (FC / Fluct).")
						: t(lang, "; cũng chưa có target.", "; no targets either."));
			} else {
				List<String> parts = new ArrayList<>();
				if (tsl > 0) {
					parts.add(t(lang, "Actual T&SL đến ", "Actual T&SL up to ") + CostMonitoringSnapshot.ddmm(month.atDay(tsl)));
				}
				if (rm > 0) {
					parts.add(t(lang, "RM đến ", "RM up to ") + CostMonitoringSnapshot.ddmm(month.atDay(rm)));
				}
				text = label + t(lang, "**có dữ liệu** – ", "**data available** – ") + String.join(", ", parts)
						+ (s.monthClosed() ? t(lang, " (tháng đã kết thúc)", " (closed month)") : "")
						+ t(lang, "; OT chưa có Actual (chỉ có FC / Fluct).", "; OT has no Actual (FC / Fluct only).");
			}
		}

		String mm = String.format(Locale.ROOT, "%d", month.getMonthValue());
		List<String> suggestions = hasActual
				? List.of(t(lang, "Tháng " + mm + " có gì bất thường?", "Anything unusual in month " + mm + "?"))
				: List.of(t(lang, "Tháng này có khung nào vượt không?", "Is any frame above Fluct this month?"));

		return new AnswerGuide(null, false, "data_availability", text, suggestions,
				"dataAvailability=" + month + "; template=yes");
	}

	private AnswerGuide previousMonthOverview(String question, Map<String, String> params, String lang) {

		String folded = CostMonitoringIntentClassifier.fold(question);

		if (PREVIOUS_MONTH_COMPARE.matcher(folded).find()) {
			return null;
		}

		Map<String, String> safeParams = params != null ? params : Map.of();
		java.time.YearMonth viewed = loader.load(safeParams).month();
		java.time.YearMonth target;
		String stripped;
		boolean previous = PREVIOUS_MONTH.matcher(folded).find();

		if (previous) {
			target = viewed.minusMonths(1);
			stripped = PREVIOUS_MONTH.matcher(folded).replaceAll(" ");
		} else {
			java.util.regex.Matcher m = NAMED_MONTH.matcher(folded);
			if (!m.find()) {
				return null;
			}
			int mm = Integer.parseInt(m.group(1));
			int yyyy = m.group(2) != null ? Integer.parseInt(m.group(2))
					: mm > viewed.getMonthValue() ? viewed.getYear() - 1 : viewed.getYear();
			target = java.time.YearMonth.of(yyyy, mm);
			// Chỉ tháng đã qua so với tháng đang xem; tháng đang xem / tương lai -> luồng thường
			if (!target.isBefore(viewed)) {
				return null;
			}
			stripped = m.replaceAll(" ");
		}

		stripped = stripped.replaceAll("\\s+", " ").trim();
		String month = target.toString();

		Map<String, String> previousParams = new java.util.HashMap<>(safeParams);
		previousParams.put("month", month);
		previousParams.remove("simuWeek");
		previousParams.remove("plTgtMode");

		AnswerGuide g = byIntent(stripped, previousParams, lang);

		if (g == null || !g.hasDirectAnswer()) {
			return null;
		}

		String title = previous
				? t(lang, "**Tháng trước (" + month + "):**\n\n", "**Previous month (" + month + "):**\n\n")
				: t(lang, "**Tháng " + month + ":**\n\n", "**Month " + month + ":**\n\n");
		return new AnswerGuide(null, false, g.intent() + "_previous_month",
				title + g.directAnswer(),
				g.suggestions(), g.intentMatch() + "; previousMonth=" + month);
	}

	// Câu hỏi về 1 ngày: tổng quan tháng chỉ giữ câu tóm tắt + nhóm đầu (dự báo vượt nặng nhất) + footer;
	// các nhóm sau đã được đếm trong câu tóm tắt. Ít đoạn (không có nhóm phụ) -> giữ nguyên.
	static String compactOverview(String overview, String lang) {

		String[] parts = overview.trim().split("\n\\s*\n");

		if (parts.length <= 3) {
			return overview;
		}

		String last = parts[parts.length - 1].trim();
		boolean footer = last.startsWith("_");
		int groups = parts.length - 1 - (footer ? 1 : 0);

		if (groups <= 1) {
			return overview;
		}

		StringBuilder sb = new StringBuilder(parts[0].trim()).append("\n\n").append(parts[1].trim());
		sb.append("\n\n").append(t(lang, "_(Chi tiết các khung khác: hỏi \"Có gì bất thường trong tháng này?\")_",
				"_(Other frames: ask \"Anything unusual this month?\")_"));
		if (footer) {
			sb.append("\n\n").append(last);
		}

		return sb.toString();
	}

	// Phân loại intent (luồng trước khi có câu tra cứu)
	private AnswerGuide byIntent(String question, Map<String, String> params, String lang) {

		Plan plan = ExplainVariancePrefetchRule.plan(question, params);

		if (plan == null) {
			// Không có kế hoạch: vẫn ghi cách phân loại cho audit (câu khái niệm / xu hướng Fluct -> null)
			CostMonitoringIntentClassifier.Result r = ExplainVariancePrefetchRule.classify(question, params);
			if (TopItemsPrefetchRule.isTopItemsQuestion(question, params)) {
				// Pre-fetch get_top_items đủ trả lời -> 1 lần gọi LLM (tool_choice none khi pre-fetch thành công)
				return new AnswerGuide(null, true, TopItemsPrefetchRule.INTENT, null, List.of(),
						r == null ? "prefetch=get_top_items" : r.describe() + "; prefetch=get_top_items");
			}
			return r == null ? null : AnswerGuide.matchOnly(null, r.describe());
		}

		String intent = plan.intent().name().toLowerCase(Locale.ROOT) + (plan.atCurrentRate() ? "_current_rate" : "");
		String match = plan.match() != null ? plan.match().describe() : null;
		String folded = CostMonitoringIntentClassifier.fold(question);
		boolean detail = containsWord(folded, DETAIL_KEYWORDS);

		return switch (plan.intent()) {
			case FORECAST_ONE, FORECAST_ALL, OVERVIEW -> precomputed(plan, question, folded, params, lang, true, intent, match);
			case RANKING -> precomputed(plan, question, folded, params, lang, !detail, intent, match);
			// So sánh 2–3 khung (không hỏi vật tư / tháng trước / nguyên nhân): template từ kết luận tính sẵn.
			// Test 2026-11-11 "hôm nay Press t&sl so sánh Mold t&sl": qua LLM với 2 explain_variance bản đầy đủ -> prompt quá
			// lớn, LLM lỗi, FE báo "mất kết nối". Còn lại: LLM với khối KẾT LUẬN cho đúng các khung được hỏi.
			case COMPARE -> {
				AnswerGuide direct = detail ? null : compareTemplate(plan, folded, params, lang, intent, match);
				yield direct != null ? direct
						: new AnswerGuide(compareConclusion(plan, params, lang), !detail, intent, null, List.of(), match);
			}
			// Nguyên nhân: khối sự kiện + chỉ thị; vẫn cho gọi tool (có thể cần get_top_items / get_daily_series)
			case WHY -> {
				// Tiền đề sai ("vì sao thấp hơn target" khi thực tế chi nhanh / dự báo vượt): trả lời bằng code
				AnswerGuide premise = wrongPremise(plan, folded, params, lang, intent, match);
				if (premise != null) {
					yield premise;
				}
				// "Vì sao X tệ hơn / tốt hơn Y": so sánh 2 khung kèm kiểm tra tiền đề (template compare)
				if (!detail && plan.frames().size() == 2
						&& (CLAIM_WORSE.matcher(folded).find() || CLAIM_BETTER.matcher(folded).find())) {
					AnswerGuide versus = compareTemplate(plan, folded, params, lang, intent, match);
					if (versus != null) {
						yield versus;
					}
				}
				// "Vì sao X vượt / dự báo vượt / chi nhiều": nguyên nhân tính bằng code (template); câu hỏi thêm về vật tư /
				// Category / tháng trước / Ratio -> LLM với khối KẾT LUẬN như cũ
				if (!detail && (plan.match() == null || !plan.match().previousMonth()) && plan.costs().size() == 1
						&& !containsWord(folded, WHY_LLM_KEYWORDS)) {
					AnswerGuide why = lookupAnswers.why(new CostMonitoringFocus(plan.div(), plan.costs().get(0)), params, lang, match);
					if (why != null) {
						yield why;
					}
				}
				yield new AnswerGuide(whyConclusion(plan, params, lang), false, intent, null, List.of(), match);
			}
			case FRAME -> {
				// "X thì sao / thế nào / tình hình X" (không "đánh giá / phân tích", không hỏi vật tư…): tóm tắt tính sẵn
				if (!detail && STATUS_ASK.matcher(folded).find() && !STATUS_LLM.matcher(folded).find()
						&& plan.costs().size() == 1 && !CostMonitoringToolArgs.ALL.equals(plan.div())) {
					AnswerGuide status = lookupAnswers.frameStatus(
							new CostMonitoringFocus(plan.div(), plan.costs().get(0)), params, lang, match);
					if (status != null) {
						yield status;
					}
				}
				yield AnswerGuide.matchOnly(intent, match);
			}
		};
	}

	private static final java.util.regex.Pattern STATUS_ASK = CostMonitoringQuestion.word(
			"thi sao|the nao|ra sao|tinh hinh|on khong|sao roi|how about|what about|how is"
					// test 2026-11-10 ext-51: "đánh giá chart PRESS RM" qua LLM 36 s, kết luận sai tiến độ / ngân sách
					+ "|danh gia|nhan xet|evaluate|assess");
	// Phân tích tự do / so tháng trước / Ratio -> vẫn LLM
	private static final java.util.regex.Pattern STATUS_LLM = CostMonitoringQuestion.word(
			"phan tich|tot khong|thang truoc|cung ky|ratio|analyze|analyse");

	// Lỗi đọc dữ liệu tra cứu -> bỏ qua, đi luồng phân loại intent như cũ
	private AnswerGuide lookup(String question, Map<String, String> params, String lang) {
		try {
			return lookupAnswers.guide(question, params, lang);
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger(CostMonitoringAnswerGuide.class)
					.warn("Assistant: lookup answer failed error={}", e.getClass().getSimpleName());
			return null;
		}
	}

	// Khối KẾT LUẬN (luồng LLM) + câu trả lời template (CostMonitoringAnswerTemplate, không gọi LLM) khi câu hỏi
	// trả lời trọn được bằng kết luận tính sẵn (templateEligible). ChatOrchestrator chỉ dùng template khi
	// assistant.template-answers.enabled = true; tắt thì vẫn dùng khối KẾT LUẬN như trước.
	private AnswerGuide precomputed(Plan plan, String question, String folded, Map<String, String> params, String lang,
									boolean toolsSufficient, String intent, String match) {

		CostMonitoringSnapshot snapshot = loader.load(params);
		List<Frame> frames = frames(plan, snapshot);
		String conclusion = conclusion(plan, snapshot, frames, lang);

		if (!toolsSufficient || frames.isEmpty() || !templateEligible(plan, question, folded)) {
			return new AnswerGuide(conclusion, toolsSufficient, intent, null, List.of(), match);
		}

		return new AnswerGuide(conclusion, true, intent,
				CostMonitoringAnswerTemplate.render(plan, snapshot, frames, lang),
				CostMonitoringAnswerTemplate.suggestions(plan, snapshot, frames, lang),
				match);
	}

	// Template chỉ khi khớp CHẮC CHẮN đúng 1 intent template (CostMonitoringIntentClassifier.Result.certain: không có
	// "đánh giá", "so sánh", "tốt", "nên", "tại sao / vì sao", không khớp nhiều intent) và câu hỏi không có ý ngoài kết
	// luận tính sẵn: vật tư / máy / cost center, theo ngày, tháng trước, dự báo 1 bộ phận kèm hỏi khung khác.
	private static boolean templateEligible(Plan plan, String question, String folded) {

		if (plan.match() == null || !plan.match().certain()) {
			return false;
		}

		// Tổng quan + "hôm qua / chủ nhật…": vẫn cho template (guide() ghép phần ngày gần nhất của lookup ANOMALY_TODAY)
		boolean mergedWithLatestDay = plan.intent() == ExplainVariancePrefetchRule.Intent.OVERVIEW
				&& CostMonitoringLookupAnswers.ANOMALY_TODAY.equals(
				CostMonitoringLookupAnswers.kind(question, CostMonitoringIntentClassifier.fold(question)));
		boolean dailyBlocks = DailySeriesPrefetchRule.isDailyQuestion(question) && !mergedWithLatestDay;

		if (containsWord(folded, DETAIL_KEYWORDS)
				|| plan.match().previousMonth()
				|| dailyBlocks) {
			return false;
		}

		return plan.intent() != ExplainVariancePrefetchRule.Intent.FORECAST_ONE
				|| !CostMonitoringQuestion.asksOtherFrames(CostMonitoringQuestion.normalize(question));
	}

	private static boolean containsWord(String folded, List<String> keywords) {
		return keywords.stream().anyMatch(kw ->
				CostMonitoringQuestion.word(java.util.regex.Pattern.quote(kw)).matcher(folded).find());
	}


	// =========================================================
	// SO SÁNH CÁC KHUNG (COMPARE, luồng LLM)
	//
	// Mỗi khung được hỏi 1 dòng: % đã dùng (Actual / Fluct), tiến độ (+ Chi so với tiến độ), dự báo chính so với
	// Fluct / FC (K$ và %), rủi ro. Dòng "Khung tốt hơn theo dự báo chính = …": % Fluct dự báo thấp hơn là tốt hơn;
	// chênh < 1 điểm % -> "tương đương". Tháng đã kết thúc: theo Actual / Fluct.
	// Mọi chênh lệch tính từ số đã làm tròn 1 chữ số.
	// =========================================================

	private String compareConclusion(Plan plan, Map<String, String> params, String lang) {

		CostMonitoringSnapshot snapshot = loader.load(params);
		boolean closed = snapshot.monthClosed();
		List<Frame> frames = new ArrayList<>();

		for (CostMonitoringFocus f : plan.frames()) {
			CostChartDto chart = snapshot.find(f.div(), f.cost());
			if (chart != null) {
				frames.add(new Frame(f.div() + " " + f.cost(), CostMonitoringAssessment.of(snapshot, chart)));
			}
		}

		if (frames.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "[KẾT LUẬN – so sánh, hệ thống đã tính sẵn; tháng ", "[CONCLUSION – comparison, precomputed; month "))
				.append(snapshot.month())
				.append(t(lang, ", actual đến ngày ", ", actual up to day ")).append(snapshot.d())
				.append(closed ? t(lang, "; tháng đã kết thúc", "; month closed") : t(lang, "; dự báo: chính", "; forecast: main"))
				.append("]\n");

		for (Frame f : frames) {
			sb.append("- ").append(f.name()).append(": ").append(compareLine(f.a(), closed, lang)).append('\n');
		}

		sb.append(betterLine(frames, closed, lang)).append('\n');

		sb.append(t(lang,
				"Chỉ thị: so sánh dựa ĐÚNG KẾT LUẬN này (số, dấu); CHỈ nói về các khung trên, không nêu khung / bộ phận khác. "
						+ "\"Tốt hơn\" = dự báo cuối tháng bằng % Fluct thấp hơn (tháng đã kết thúc: Actual / Fluct thấp hơn); "
						+ "chép nguyên kết luận \"Khung tốt hơn\". Mỗi khung 1 dòng, rồi 1 câu kết. Không bảng.",
				"Instruction: compare EXACTLY from this CONCLUSION (numbers, signs); ONLY discuss the frames above, no other "
						+ "frames / divisions. \"Better\" = lower month-end forecast as % of Fluct (month closed: lower Actual / "
						+ "Fluct); copy the \"Better frame\" verdict. One line per frame, then 1 closing sentence. No table."));

		return sb.toString().trim();
	}

	// Câu trả lời template cho so sánh 2–3 khung: mỗi khung 1 dòng (như khối KẾT LUẬN, không nhãn rủi ro) + kết luận
	// khung tốt hơn (betterLine). Không áp dụng: hỏi tháng trước / cùng kỳ, nguyên nhân, < 2 hoặc > 3 khung, thiếu khung.
	private static final Pattern COMPARE_LLM = CostMonitoringQuestion.word(
			"vi sao|tai sao|nguyen nhan|ly do|why|cause|reason|nen|should");

	private AnswerGuide compareTemplate(Plan plan, String folded, Map<String, String> params, String lang,
										String intent, String match) {

		// Drawer + "so sánh với MOLD" (câu chỉ nêu 1 khung khác khung đang mở): so khung drawer với khung đó
		// (test 2026-11-11 1303 ext-131: chỉ lấy MOLD T&SL, qua LLM 10 s, sai chính tả "Rủi rò")
		List<CostMonitoringFocus> targets = new ArrayList<>(plan.frames());
		CostMonitoringFocus drawer = CostMonitoringFocus.from(params);
		if (targets.size() == 1 && drawer != null && !targets.contains(drawer)) {
			targets.add(0, drawer);
		}

		// "Vì sao X tệ hơn / tốt hơn Y" (2 khung): template kèm kiểm tra tiền đề (1303 ext-130: LLM 42 s, "108.4% … trong khi
		// MOLD chỉ 118.7%" rồi kết luận PRESS rủi ro hơn – ngược với % Fluct)
		boolean claimWorse = CLAIM_WORSE.matcher(folded).find();
		boolean claimBetter = !claimWorse && CLAIM_BETTER.matcher(folded).find();
		boolean premiseCheck = (claimWorse || claimBetter) && targets.size() == 2;

		if (targets.size() < 2 || targets.size() > 3 || (COMPARE_LLM.matcher(folded).find() && !premiseCheck)
				|| (plan.match() != null && plan.match().previousMonth())) {
			return null;
		}

		CostMonitoringSnapshot snapshot = loader.load(params);
		boolean closed = snapshot.monthClosed();
		List<Frame> frames = new ArrayList<>();

		for (CostMonitoringFocus f : targets) {
			CostChartDto chart = snapshot.find(f.div(), f.cost());
			if (chart == null) {
				return null;
			}
			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
			if (a.act() == null || a.fluct() == null) {
				return null;
			}
			frames.add(new Frame(f.div() + " " + f.cost(), a));
		}

		StringBuilder sb = new StringBuilder();
		if (premiseCheck) {
			String premise = premiseLine(frames.get(0), frames.get(1), claimWorse, closed, lang);
			if (premise == null) {
				return null;
			}
			sb.append(premise).append("\n\n");
		}

		List<String> names = frames.stream().map(Frame::name).toList();
		sb.append(t(lang, "**So sánh ", "**Comparison: "))
				.append(names.size() == 2 ? String.join(t(lang, " và ", " vs "), names)
						: String.join(", ", names.subList(0, names.size() - 1)) + t(lang, " và ", " and ") + names.get(names.size() - 1))
				.append(closed ? t(lang, "** (tháng đã kết thúc):", "** (month closed):")
						: t(lang, "** (tháng tính đến ngày có actual mới nhất):", "** (month to date):"))
				.append('\n');

		for (Frame f : frames) {
			sb.append("- **").append(f.name()).append("**: ").append(compareLine(f.a(), closed, lang, false)).append('\n');
		}

		sb.append('\n').append("**").append(betterLine(frames, closed, lang).replace(" = ", ": ")).append("**\n\n");
		sb.append("_").append(t(lang, "Số liệu tháng ", "Data for month ")).append(snapshot.month())
				.append(closed ? t(lang, " (đã kết thúc).", " (closed).")
						: t(lang, ", actual đến ngày ", ", actual up to day ") + snapshot.d()
						+ t(lang, "; \"tốt hơn\" = dự báo cuối tháng bằng % Fluct thấp hơn.",
						"; \"better\" = lower month-end forecast as % of Fluct."))
				.append("_");

		String first = frames.get(0).name();
		return new AnswerGuide(null, false, intent, sb.toString().trim(), List.of(
				t(lang, "Vì sao " + first + " dự báo vượt?", "Why is " + first + " forecast to exceed?"),
				t(lang, "Ngày nào " + first + " chi nhiều nhất?", "Which day did " + first + " spend the most?")),
				match + "; template=compare");
	}

	// "đã dùng 85.3% Fluct; tiến độ: Chi nhanh hơn tiến độ (+3.2 K$); dự báo cuối tháng 231.0 K$ (theo PL) – vượt Fluct
	//  +46.7 K$ (+25.3%), vượt FC +55.8 K$ (+31.8%); rủi ro: Rủi ro cao"
	private static String compareLine(CostMonitoringAssessment a, boolean closed, String lang) {
		return compareLine(a, closed, lang, true);
	}

	// withRisk = false: bỏ nhãn rủi ro (câu trả lời template cho người đọc)
	private static String compareLine(CostMonitoringAssessment a, boolean closed, String lang, boolean withRisk) {

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "đã dùng ", "used ")).append(a.pct() == null ? CmText.noData(lang) : CmText.pct1(a.pct()))
				.append(t(lang, " Fluct (Actual ", " of Fluct (Actual ")).append(CmText.k1(a.act()))
				.append("; Fluct ").append(CmText.k1(a.fluct())).append("; FC ").append(CmText.k1(a.fc())).append(')');

		if (closed) {
			return sb.append("; ").append(CmText.monthResult(a, lang))
					.append("; ").append(CmText.versus("FC", CmText.subR1(a.act(), a.fc()), a.actOverFcPct(), lang))
					.toString();
		}

		// "Đúng tiến độ" (trong dung sai) nhưng chênh khác 0 -> ghi rõ nhanh / chậm, khớp frame_status / trend
		// (1303 ext-125: PRESS RM "Đúng tiến độ (+10.7 K$)" trong khi câu khác ghi "chi nhanh hơn tiến độ")
		Double excess = a.excessVsPace();
		sb.append(t(lang, "; tiến độ: ", "; pace: "));
		if (a.paceStatus() == null) {
			sb.append(CmText.noData(lang));
		} else if (CostMonitoringAssessment.ON_PACE.equals(a.paceStatus()) && excess != null && CmText.r1(excess) != 0) {
			sb.append(excess > 0 ? t(lang, "sát tiến độ, hơi nhanh (", "close to pace, slightly faster (")
							: t(lang, "sát tiến độ, hơi chậm (", "close to pace, slightly slower ("))
					.append(CmText.signedK(excess)).append(')');
		} else {
			String pace = CmText.pace(a.paceStatus(), lang);
			sb.append(Character.toLowerCase(pace.charAt(0))).append(pace.substring(1));
			if (excess != null) {
				sb.append(" (").append(t(lang, "so với tiến độ ", "vs pace ")).append(CmText.signedK(excess)).append(')');
			}
		}

		if (a.estimateEom() == null) {
			sb.append(t(lang, "; dự báo: chưa có", "; forecast: none"));
		} else {
			String base = CmText.forecastBase(a.estimateBase(), lang);
			sb.append(t(lang, "; dự báo cuối tháng ", "; month-end forecast ")).append(CmText.k1(a.estimateEom()))
					.append(base.isEmpty() ? "" : " (" + base + ")")
					.append(" – ").append(CmText.versus("Fluct", a.estimateOver(), a.estimateOverPct(), lang))
					.append(", ").append(CmText.versus("FC", a.estimateOverFc(), a.estimateOverFcPct(), lang));
		}

		if (!withRisk) {
			return sb.toString();
		}

		return sb.append(t(lang, "; rủi ro: ", "; risk: "))
				.append(a.risk() == null ? CmText.noData(lang) : CmText.risk(a.risk(), lang)).toString();
	}

	// "Khung tốt hơn theo dự báo chính = MOLD T&SL (bằng 92.1% Fluct, thấp hơn PRESS T&SL 33.2 điểm %)"
	// / "= tương đương PRESS T&SL và MOLD T&SL (chênh 0.4 điểm %)"
	private static String betterLine(List<Frame> frames, boolean closed, String lang) {

		String label = closed
				? t(lang, "Khung tốt hơn theo Actual / Fluct (tháng đã kết thúc) = ",
				"Better frame by Actual / Fluct (month closed) = ")
				: t(lang, "Khung tốt hơn theo dự báo chính = ", "Better frame by main forecast = ");

		List<Frame> ranked = frames.stream()
				.filter(f -> comparePct(f.a(), closed) != null)
				.sorted(Comparator.comparing((Frame f) -> comparePct(f.a(), closed)))
				.toList();

		if (ranked.size() < 2) {
			return label + t(lang, "chưa đủ dữ liệu để so sánh", "not enough data to compare");
		}

		Frame best = ranked.get(0);
		Frame second = ranked.get(1);
		double diff = CmText.r1(comparePct(second.a(), closed) - comparePct(best.a(), closed));

		if (diff < COMPARE_EQUAL_POINTS) {
			return label + t(lang, "tương đương ", "equivalent: ") + best.name() + t(lang, " và ", " and ") + second.name()
					+ " (" + CmText.pct1(comparePct(best.a(), closed)) + " / " + CmText.pct1(comparePct(second.a(), closed))
					+ t(lang, " Fluct, chênh ", " of Fluct, gap ") + String.format(Locale.ROOT, "%.1f", diff)
					+ t(lang, " điểm %)", " pts)");
		}

		return label + best.name() + " (" + t(lang, "bằng ", "at ") + CmText.pct1(comparePct(best.a(), closed))
				+ t(lang, " Fluct, thấp hơn ", " of Fluct, lower than ") + second.name() + " "
				+ String.format(Locale.ROOT, "%.1f", diff) + t(lang, " điểm %)", " pts)");
	}

	private static final Pattern CLAIM_WORSE = CostMonitoringQuestion.word(
			"te hon|kem hon|xau hon|toi hon|nguy hon|rui ro hon|worse|riskier");
	private static final Pattern CLAIM_BETTER = CostMonitoringQuestion.word(
			"tot hon|on hon|it rui ro hon|better|safer");

	// Câu đầu cho "vì sao X tệ hơn / tốt hơn Y": đúng / sai theo % Fluct (cùng tiêu chí betterLine); kèm K$ vượt nếu
	// 2 tiêu chí cho kết quả ngược nhau (target lớn thì K$ vượt lớn dù % thấp). null = thiếu số -> LLM.
	private static String premiseLine(Frame x, Frame y, boolean claimWorse, boolean closed, String lang) {

		Double px = comparePct(x.a(), closed);
		Double py = comparePct(y.a(), closed);
		if (px == null || py == null) {
			return null;
		}

		String criterion = closed ? t(lang, "Actual / Fluct (tháng đã kết thúc)", "Actual / Fluct (month closed)")
				: t(lang, "dự báo cuối tháng / Fluct", "month-end forecast / Fluct");
		String pcts = " (" + CmText.pct1(px) + t(lang, " so với ", " vs ") + CmText.pct1(py) + t(lang, " Fluct)", " of Fluct)");
		double diff = CmText.r1(Math.abs(px - py));

		StringBuilder sb = new StringBuilder();

		if (diff < COMPARE_EQUAL_POINTS) {
			sb.append(t(lang, "**Theo " + criterion + ", " + x.name() + " và " + y.name() + " tương đương**",
					"**By " + criterion + ", " + x.name() + " and " + y.name() + " are about the same**")).append(pcts).append('.');
		} else {
			boolean xWorse = px > py;
			boolean holds = claimWorse == xWorse;
			String verdict = xWorse ? t(lang, " tệ hơn ", " is worse than ") : t(lang, " tốt hơn ", " is better than ");
			sb.append(holds ? "" : t(lang, "**Tiền đề chưa đúng:** ", "**The premise does not hold:** "))
					.append(t(lang, "theo " + criterion + ", ", "by " + criterion + ", "))
					.append("**").append(x.name()).append(verdict).append(y.name()).append("**").append(pcts).append('.');
		}

		// K$ vượt (dự báo / Actual so với Fluct) – ghi khi xếp ngược với % Fluct
		Double ox = closed ? CmText.subR1(x.a().act(), x.a().fluct()) : CmText.r1(x.a().estimateOver());
		Double oy = closed ? CmText.subR1(y.a().act(), y.a().fluct()) : CmText.r1(y.a().estimateOver());
		if (ox != null && oy != null && diff >= COMPARE_EQUAL_POINTS && (ox > oy) != (px > py) && !ox.equals(oy)) {
			Frame bigK = ox > oy ? x : y;
			Frame smallK = bigK == x ? y : x;
			sb.append(t(lang, " Nếu xét theo K$ vượt Fluct thì ", " By K$ above Fluct, "))
					.append(bigK.name()).append(t(lang, " lớn hơn (", " is larger ("))
					.append(CmText.signedK(bigK == x ? ox : oy)).append(t(lang, " so với ", " vs "))
					.append(CmText.signedK(bigK == x ? oy : ox)).append(t(lang, ") vì target lớn hơn (Fluct ", ") because its target is larger (Fluct "))
					.append(CmText.k1(bigK.a().fluct())).append(t(lang, " so với ", " vs ")).append(CmText.k1(smallK.a().fluct()))
					.append(").");
		}

		return sb.toString();
	}

	// % Fluct dùng để so (làm tròn 1 chữ số): dự báo chính; tháng đã kết thúc: Actual / Fluct
	private static Double comparePct(CostMonitoringAssessment a, boolean closed) {
		return CmText.r1(closed ? a.pct() : a.estimatePctOfFluct());
	}


	// =========================================================
	// "VÌ SAO VƯỢT" (1 khung): nguồn nguyên nhân hợp lệ + Ratio đúng chiều
	//   Chưa vượt Fluct : Chi vượt tiến độ (excessVsPace)
	//   Đã vượt Fluct   : Phần vượt Fluct = Target bị hạ (FC − Fluct) + Chi vượt FC (Actual − FC)
	//   Fluct so với FC : Fluct > FC (Ratio dương, target được nâng) -> GIẢM khả năng vượt, KHÔNG phải nguyên nhân;
	//                     Fluct < FC (Ratio âm, target bị hạ) -> góp phần vào phần vượt Fluct
	// =========================================================

	// "Vì sao … thấp hơn target / chi ít" mà thực tế chi nhanh hơn tiến độ / dự báo vượt / đã vượt, hoặc
	// "vì sao … vượt / cao" mà thực tế trong tiến độ, dự báo trong Fluct và FC -> nói rõ tiền đề không đúng (template).
	// Audit 2026-11-10: "Vì sao GUIDE RM thấp hơn target?" -> LLM giải thích theo Fluct < FC dù GUIDE RM dự báo vượt.
	private static final Pattern PREMISE_LOW = CostMonitoringQuestion.word(
			"thap hon|chi it|it hon|duoi target|duoi fluct|duoi fc|tiet kiem|lower|below|under target|under budget");
	private static final Pattern PREMISE_HIGH = CostMonitoringQuestion.word(
			"vuot|cao hon|nhieu hon|qua tay|over|exceed|exceeds|exceeded|above|higher");

	private AnswerGuide wrongPremise(Plan plan, String folded, Map<String, String> params, String lang,
									 String intent, String match) {

		boolean low = PREMISE_LOW.matcher(folded).find();
		boolean high = PREMISE_HIGH.matcher(folded).find();

		if (low == high || plan.costs().size() != 1) {
			return null;
		}

		CostMonitoringSnapshot snapshot = loader.load(params);
		String cost = plan.costs().get(0);
		CostChartDto chart = snapshot.find(plan.div(), cost);

		if (chart == null || snapshot.monthClosed()) {
			return null;
		}

		CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);

		if (a.act() == null || a.fluct() == null) {
			return null;
		}

		boolean aheadOfPace = positive(a.excessVsPace());
		boolean overSignal = a.alreadyOver() || a.estimateWillExceed() || aheadOfPace;
		boolean fcOver = positive(a.estimateOverFc());

		if (low ? !overSignal : (overSignal || fcOver)) {
			return null;
		}

		String name = plan.div() + " " + cost;
		StringBuilder sb = new StringBuilder("**").append(name).append("** ")
				.append(low
						? t(lang, "thực tế **không thấp hơn** target", "is actually **not below** target")
						: t(lang, "thực tế **không vượt** target", "is actually **not above** target"))
				.append(":\n");

		sb.append("- Actual ").append(CmText.k1(a.act()));
		if (a.excessVsPace() != null) {
			sb.append(t(lang, ", so với tiến độ (Fluct × D / N) ", ", vs pace (Fluct × D / N) "))
					.append(CmText.signedK(a.excessVsPace(),
							t(lang, "chi nhanh hơn tiến độ", "ahead of pace"),
							t(lang, "chi chậm hơn tiến độ", "behind pace")));
		}
		if (a.alreadyOver()) {
			sb.append(t(lang, "; đã vượt Fluct ", "; already above Fluct "))
					.append(CmText.signedK(CmText.subR1(a.act(), a.fluct())));
		}
		sb.append('\n');

		appendOne(sb, List.of(new Frame(name, a)), false, lang);

		if (positive(CmText.subR1(a.fc(), a.fluct()))) {
			sb.append('\n').append(t(lang, "Fluct thấp hơn FC ", "Fluct is below FC "))
					.append(CmText.signedK(CmText.subR1(a.fluct(), a.fc())))
					.append(t(lang, " (Ratio âm, target bị hạ) – đây là mức target, không có nghĩa là chi thấp hơn target.",
							" (negative Ratio, target cut) – this is the target level, not lower spending."));
		}

		sb.append("\n\n_").append(t(lang, "Số liệu tháng ", "Data for month ")).append(snapshot.month())
				.append(t(lang, ", actual đến ngày ", ", actual up to day ")).append(snapshot.d()).append("._");

		List<String> suggestions = low
				? List.of(t(lang, "Vì sao " + name + " dự báo vượt?", "Why is " + name + " forecast to exceed?"),
				t(lang, "Ngày nào " + name + " chi nhiều nhất?", "Which day did " + name + " spend the most?"))
				: List.of(t(lang, "Cứ chi như vậy thì " + name + " có vượt FC không?",
				"If spending continues like this, will " + name + " exceed FC?"));

		return new AnswerGuide(null, false, intent + "_premise", sb.toString().trim(), suggestions,
				match + "; premise=" + (low ? "low" : "high") + "_mismatch");
	}

	private String whyConclusion(Plan plan, Map<String, String> params, String lang) {

		CostMonitoringSnapshot snapshot = loader.load(params);
		String cost = plan.costs().get(0);
		CostChartDto chart = snapshot.find(plan.div(), cost);

		if (chart == null) {
			return null;
		}

		CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
		String name = plan.div() + " " + cost;

		if (a.act() == null || a.fluct() == null) {
			return null;
		}

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "[KẾT LUẬN – nguyên nhân, hệ thống đã tính sẵn; ", "[CONCLUSION – causes, precomputed; "))
				.append(name).append(t(lang, "; tháng ", "; month ")).append(snapshot.month())
				.append(t(lang, ", actual đến ngày ", ", actual up to day ")).append(snapshot.d()).append("]\n");

		sb.append("- ").append(CmText.mode(a.mode(), lang))
				.append(": Actual ").append(CmText.k1(a.act()))
				.append("; Fluct ").append(CmText.k1(a.fluct()))
				.append("; FC ").append(CmText.k1(a.fc())).append('\n');

		if (a.alreadyOver()) {
			sb.append(t(lang, "- Phần vượt Fluct ", "- Excess over Fluct ")).append(CmText.signedK(CmText.subR1(a.act(), a.fluct())))
					.append(t(lang, " = Target bị hạ (FC − Fluct) ", " = Target cut (FC − Fluct) "))
					.append(CmText.signedK(CmText.subR1(a.fc(), a.fluct())))
					.append(t(lang, " + Chi vượt FC (Actual − FC) ", " + Spending above FC (Actual − FC) "))
					.append(CmText.signedK(CmText.subR1(a.act(), a.fc()))).append('\n');
		} else if (a.excessVsPace() != null) {
			sb.append(t(lang, "- Chi vượt tiến độ (Actual − Fluct × D / N) ", "- Spending ahead of pace (Actual − Fluct × D / N) "))
					.append(CmText.signedK(a.excessVsPace()))
					.append(positive(a.excessVsPace())
							? t(lang, " (chi nhiều hơn tiến độ – nguyên nhân chính của dự báo vượt)",
							" (spending faster than pace – main cause of the forecast excess)")
							: t(lang, " (chi trong tiến độ)", " (within pace)"))
					.append('\n');
		}

		Double fluctVsFc = CmText.subR1(a.fluct(), a.fc());

		if (positive(fluctVsFc)) {
			sb.append(t(lang, "- Fluct cao hơn FC ", "- Fluct above FC ")).append(CmText.signedK(fluctVsFc))
					.append(t(lang,
							" (Ratio dương, target được nâng): làm GIẢM khả năng vượt, KHÔNG phải nguyên nhân vượt.\n",
							" (positive Ratio, target raised): REDUCES the chance of exceeding, NOT a cause.\n"));
		} else if (fluctVsFc != null && Math.round(fluctVsFc * 10) < 0) {
			sb.append(t(lang, "- Fluct thấp hơn FC ", "- Fluct below FC ")).append(CmText.signedK(fluctVsFc))
					.append(t(lang,
							" (Ratio âm, target bị hạ): góp phần làm vượt Fluct.\n",
							" (negative Ratio, target cut): contributes to exceeding Fluct.\n"));
		}

		sb.append(t(lang,
				"Chỉ thị: nguyên nhân CHỈ lấy từ (1) chi vượt tiến độ / chi vượt FC / target bị hạ ở trên và (2) ngày, vật tư chi "
						+ "lớn trong kết quả explain_variance ([3 ngày chi lớn nhất], [Vật tư xuất ngày …], [Khoản lớn bất "
						+ "thường], vật tư / Category tăng mạnh nhất) kèm số. CẤM nêu Ratio dương / Fluct cao hơn FC là nguyên "
						+ "nhân vượt. Không suy luận về máy, phân bổ, hiệu suất.",
				"Instruction: causes ONLY from (1) spending ahead of pace / above FC / target cut above and (2) large days and "
						+ "items in the explain_variance result, with numbers. NEVER cite a positive Ratio / Fluct above FC as a "
						+ "cause. Do not speculate about machines, allocation, efficiency."));

		return sb.toString().trim();
	}


	// =========================================================
	// KHỐI KẾT LUẬN
	// =========================================================

	record Frame(String name, CostMonitoringAssessment a) {
	}

	// Các khung của câu hỏi (dùng chung khối KẾT LUẬN và template), sắp xếp: tháng đã kết thúc theo Actual / Fluct;
	// "cứ chi như vậy" theo % Fluct dự báo theo tốc độ chi hiện tại; còn lại theo % Fluct dự báo chính (giảm dần)
	static List<Frame> frames(Plan plan, CostMonitoringSnapshot snapshot) {

		boolean one = plan.intent() == ExplainVariancePrefetchRule.Intent.FORECAST_ONE;
		List<String> divs = one ? List.of(plan.div()) : CostMonitoringSnapshot.DIVS;
		List<Frame> frames = new ArrayList<>();

		for (String cost : plan.costs()) {
			for (String div : divs) {
				CostChartDto chart = snapshot.find(div, cost);
				if (chart != null) {
					frames.add(new Frame(div + " " + cost, CostMonitoringAssessment.of(snapshot, chart)));
				}
			}
		}

		frames.sort(snapshot.monthClosed()
				? Comparator.comparing((Frame f) -> f.a().pct(), Comparator.nullsLast(Comparator.reverseOrder()))
				: Comparator.comparing(Frame::a, plan.atCurrentRate()
				? CostMonitoringAssessment.BY_PROJECTED_PCT_DESC
				: CostMonitoringAssessment.BY_ESTIMATE_PCT_DESC));

		return frames;
	}

	private String conclusion(Plan plan, CostMonitoringSnapshot snapshot, List<Frame> frames, String lang) {

		boolean closed = snapshot.monthClosed();
		boolean currentRate = plan.atCurrentRate();
		boolean ranking = plan.intent() == ExplainVariancePrefetchRule.Intent.RANKING;
		boolean one = plan.intent() == ExplainVariancePrefetchRule.Intent.FORECAST_ONE;

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "[KẾT LUẬN – hệ thống đã tính sẵn", "[CONCLUSION – precomputed by the system"))
				.append(t(lang, "; tháng ", "; month ")).append(snapshot.month())
				.append(t(lang, ", actual đến ngày ", ", actual up to day ")).append(snapshot.d())
				.append(t(lang, "; loại chi phí: ", "; cost types: ")).append(String.join(", ", plan.costs()))
				.append(closed ? "" : currentRate
						? t(lang, "; dự báo: theo tốc độ chi hiện tại", "; forecast: at current spending rate")
						: t(lang, "; dự báo: chính", "; forecast: main") + baseLabel(frames, lang))
				.append("]\n");

		int groups;

		if (closed) {
			groups = 0;
		} else if (one) {
			appendOne(sb, frames, currentRate, lang);
			groups = 1;
		} else if (ranking) {
			appendRanking(sb, frames, lang, false);
			appendTopByAmount(sb, frames, lang);
			groups = 1;
		} else if (currentRate) {
			groups = appendProjectedGroups(sb, frames, lang, false);
		} else {
			groups = appendEstimateGroups(sb, frames, lang, false);
		}

		if (closed && ranking) {
			appendClosedRanking(sb, frames, lang, false);
			groups++;
		}

		// 1 khung, tháng đang diễn ra: "đã vượt" đã ghi ngay trong dòng của khung (appendOne)
		if ((!one || closed) && appendAlreadyOver(sb, frames, closed, lang, false)) {
			groups++;
		}

		// Mọi nhóm rỗng: 1 dòng khẳng định (không liệt kê nhóm rỗng)
		if (groups == 0) {
			sb.append(closed
					? t(lang, "Mọi khung: kết quả tháng trong Fluct.\n", "All frames: month result within Fluct.\n")
					: t(lang,
					"Mọi khung: dự báo cuối tháng trong Fluct và FC; Actual trong Fluct.\n",
					"All frames: month-end forecast within Fluct and FC; Actual within Fluct.\n"));
		}

		sb.append(instruction(frames, closed, ranking, one, currentRate, lang));

		return sb.toString().trim();
	}

	// Dự báo CHÍNH: (a) dự báo vượt Fluct, (a2) vượt FC nhưng trong Fluct, (r) chỉ vượt theo tốc độ chi hiện tại.
	// Khung đã vượt thực tế thuộc (b), không lặp ở đây. Trả về số nhóm đã ghi.
	// md: tiêu đề nhóm dạng markdown (câu trả lời template); false: dạng khối KẾT LUẬN
	static int appendEstimateGroups(StringBuilder sb, List<Frame> frames, String lang, boolean md) {

		List<Frame> willExceed = new ArrayList<>();
		List<Frame> overFcOnly = new ArrayList<>();
		List<Frame> rateOnly = new ArrayList<>();

		for (Frame f : frames) {

			String status = f.a().mainStatus();

			if (CostChartForecastDto.STATUS_WILL_EXCEED.equals(status)) {
				willExceed.add(f);
			} else if (CostChartForecastDto.STATUS_WITHIN.equals(status)) {
				if (positive(f.a().estimateOverFc())) {
					overFcOnly.add(f);
				} else if (positive(f.a().projectedOver())) {
					rateOnly.add(f);
				}
			}
		}

		int groups = 0;

		if (!willExceed.isEmpty()) {
			// md (template): tiêu đề ngắn, không in hoa; cơ sở dự báo (PL / nhịp Fluct) ghi ở dòng cuối
			appendEstimateGroup(sb, md
					? t(lang, "Dự báo vượt Fluct", "Forecast above Fluct")
					: t(lang,
					"Dự báo cuối tháng VƯỢT Fluct (chính)" + baseLabel(willExceed, lang),
					"Month-end forecast ABOVE Fluct (main)" + baseLabel(willExceed, lang)), willExceed, lang, md);
			groups++;
		}

		if (!overFcOnly.isEmpty()) {
			appendEstimateGroup(sb, md
					? t(lang, "Dự báo vượt FC nhưng trong Fluct", "Forecast above FC but within Fluct")
					: t(lang,
					"Dự báo VƯỢT FC nhưng vẫn trong Fluct (chính)",
					"Forecast ABOVE FC but within Fluct (main)"), overFcOnly, lang, md);
			groups++;
		}

		if (!rateOnly.isEmpty()) {
			title(sb, md
					? t(lang, "Theo tốc độ hiện tại thì vượt (tham khảo)", "Above at the current rate (reference)")
					: t(lang,
					"Trong Fluct theo dự báo chính, nhưng theo tốc độ chi hiện tại thì vượt (tham khảo)",
					"Within Fluct by main forecast, but above at current spending rate (reference)"),
					rateOnly.size(), lang, md);
			for (Frame f : rateOnly) {
				sb.append("- ").append(f.name()).append(": ").append(CmText.estimateBrief(f.a(), lang))
						.append("; ").append(CmText.projectedNote(f.a(), lang)).append('\n');
			}
			groups++;
		}

		return groups;
	}

	// Tiêu đề nhóm "Tiêu đề (N khung):" (count null -> không ghi số khung);
	// md: dòng trống trước + in đậm (câu trả lời template)
	static void title(StringBuilder sb, String text, Integer count, String lang, boolean md) {

		String s = text + (count == null ? "" : " (" + count + t(lang, " khung", " frames") + ")") + ":";

		if (md) {
			sb.append("\n**").append(s).append("**\n");
		} else {
			sb.append(s).append('\n');
		}
	}

	private static void appendEstimateGroup(StringBuilder sb, String title, List<Frame> list, String lang, boolean md) {

		title(sb, title, list.size(), lang, md);

		for (Frame f : list) {
			sb.append("- ").append(f.name()).append(": ").append(CmText.estimateBrief(f.a(), lang))
					.append(disagreeNote(f.a(), lang)).append('\n');
		}
	}

	// Câu "cứ chi như vậy" (chung): nhóm theo tốc độ chi hiện tại
	static int appendProjectedGroups(StringBuilder sb, List<Frame> frames, String lang, boolean md) {

		List<Frame> overFluct = new ArrayList<>();
		List<Frame> overFcOnly = new ArrayList<>();

		for (Frame f : frames) {
			if (positive(f.a().projectedOver())) {
				overFluct.add(f);
			} else if (positive(f.a().projectedOverFc())) {
				overFcOnly.add(f);
			}
		}

		int groups = 0;

		if (!overFluct.isEmpty()) {
			appendProjectedGroup(sb, md
					? t(lang, "Dự báo vượt Fluct (theo tốc độ hiện tại)", "Projected above Fluct (current rate)")
					: t(lang,
					"Theo tốc độ chi hiện tại, dự báo cuối tháng VƯỢT Fluct",
					"At current spending rate, month-end projection ABOVE Fluct"), overFluct, lang, md);
			groups++;
		}

		if (!overFcOnly.isEmpty()) {
			appendProjectedGroup(sb, md
					? t(lang, "Dự báo vượt FC nhưng trong Fluct (theo tốc độ hiện tại)",
					"Projected above FC but within Fluct (current rate)")
					: t(lang,
					"Theo tốc độ chi hiện tại, dự báo VƯỢT FC nhưng trong Fluct",
					"At current spending rate, projected ABOVE FC but within Fluct"), overFcOnly, lang, md);
			groups++;
		}

		return groups;
	}

	private static void appendProjectedGroup(StringBuilder sb, String title, List<Frame> list, String lang, boolean md) {

		title(sb, title, list.size(), lang, md);

		for (Frame f : list) {
			sb.append("- ").append(f.name()).append(": ").append(CmText.projectedBrief(f.a(), lang)).append('\n');
		}
	}

	// Dự báo 1 khung (hoặc vài loại chi phí của 1 bộ phận)
	static void appendOne(StringBuilder sb, List<Frame> frames, boolean currentRate, String lang) {

		for (Frame f : frames) {

			sb.append("- ").append(f.name()).append(": ");

			if (currentRate) {
				sb.append(t(lang, "theo tốc độ chi hiện tại ", "at current spending rate "))
						.append(CmText.rate(f.a().currentRate(), lang)).append(", ")
						.append(CmText.projectedBrief(f.a(), lang));
			} else {
				sb.append(CmText.estimateBrief(f.a(), lang)).append(disagreeNote(f.a(), lang));
			}

			if (f.a().alreadyOver()) {
				sb.append(t(lang, "; Actual đã vượt Fluct ", "; Actual already above Fluct "))
						.append(CmText.signedK(CmText.subR1(f.a().act(), f.a().fluct())));
			}

			sb.append('\n');
		}
	}

	// Xếp hạng mọi khung theo % Fluct dự báo chính (giảm dần)
	static void appendRanking(StringBuilder sb, List<Frame> frames, String lang, boolean md) {

		title(sb, md
				? t(lang, "Xếp hạng theo dự báo cuối tháng (% Fluct, cao → thấp)",
				"Ranking by month-end forecast (% of Fluct, high → low)")
				: t(lang,
				"Xếp hạng theo dự báo cuối tháng (chính) bằng % Fluct (cao → thấp)" + baseLabel(frames, lang),
				"Ranking by month-end forecast (main) as % of Fluct (high → low)" + baseLabel(frames, lang)),
				null, lang, md);

		int i = 0;

		for (Frame f : frames) {
			sb.append(++i).append(". ").append(f.name()).append(": ").append(CmText.estimateBrief(f.a(), lang))
					.append(disagreeNote(f.a(), lang)).append('\n');
		}
	}

	static void appendClosedRanking(StringBuilder sb, List<Frame> frames, String lang, boolean md) {

		title(sb, t(lang,
				"Xếp hạng theo Actual bằng % Fluct (tháng đã kết thúc, cao → thấp)",
				"Ranking by Actual as % of Fluct (month closed, high → low)"), null, lang, md);

		int i = 0;

		for (Frame f : frames) {
			sb.append(++i).append(". ").append(f.name()).append(": ")
					.append(t(lang, "Actual bằng ", "Actual at ")).append(CmText.pct1(f.a().pct()))
					.append(t(lang, " Fluct; ", " of Fluct; ")).append(CmText.monthResult(f.a(), lang))
					.append('\n');
		}
	}

	// Khung dự báo (chính) vượt Fluct nhiều nhất theo K$; không khung nào vượt -> bỏ
	private static void appendTopByAmount(StringBuilder sb, List<Frame> frames, String lang) {

		topByAmount(frames)
				.ifPresent(f -> sb.append(t(lang,
								"Dự báo vượt Fluct nhiều nhất theo K$: ",
								"Largest forecast excess over Fluct in K$: "))
						.append(f.name()).append(' ')
						.append(CmText.signedK(f.a().estimateOver()))
						.append(" (").append(CmText.signedPct1(f.a().estimateOverPct())).append(")\n"));
	}

	// Khung dự báo (chính) vượt Fluct nhiều nhất theo K$ (số đã làm tròn); không khung nào vượt -> rỗng
	static Optional<Frame> topByAmount(List<Frame> frames) {
		return frames.stream()
				.filter(f -> positive(f.a().estimateOver()))
				.max(Comparator.comparing((Frame f) -> CmText.r1(f.a().estimateOver())));
	}

	// (b) khung Actual đã vượt Fluct (tháng đã kết thúc: kết quả tháng vượt Fluct). false = không có (không ghi)
	static boolean appendAlreadyOver(StringBuilder sb, List<Frame> frames, boolean closed, String lang, boolean md) {

		List<Frame> over = frames.stream().filter(f -> f.a().alreadyOver()).toList();

		if (over.isEmpty()) {
			return false;
		}

		title(sb, closed
				? t(lang, "Kết quả tháng vượt Fluct", "Month result above Fluct")
				: md
				? t(lang, "Đã vượt Fluct (thực tế)", "Already above Fluct")
				: t(lang, "Actual ĐÃ vượt Fluct (thực tế)", "Actual ALREADY above Fluct"), over.size(), lang, md);

		for (Frame f : over) {
			sb.append("- ").append(f.name()).append(": Actual ").append(CmText.k1(f.a().act()))
					.append("; ").append(CmText.versus("Fluct", CmText.subR1(f.a().act(), f.a().fluct()), null, lang));
			if (!closed && f.a().estimateEom() != null) {
				sb.append(t(lang, "; dự báo ", "; forecast ")).append(CmText.estimateBrief(f.a(), lang));
			}
			sb.append('\n');
		}

		return true;
	}

	// "; theo tốc độ chi hiện tại thì …" khi 2 cách trái chiều
	static String disagreeNote(CostMonitoringAssessment a, String lang) {
		return a.projectionsDisagree() ? "; " + CmText.projectedNote(a, lang) : "";
	}

	private static String instruction(
			List<Frame> frames, boolean closed, boolean ranking, boolean one, boolean currentRate, String lang) {

		boolean anyForecastOver = !closed && frames.stream().anyMatch(f -> currentRate
				? positive(f.a().projectedOver()) || positive(f.a().projectedOverFc())
				: f.a().estimateWillExceed() || f.a().alreadyOver() || positive(f.a().estimateOverFc()));

		StringBuilder sb = new StringBuilder(t(lang,
				"Chỉ thị: trả lời dựa ĐÚNG trên KẾT LUẬN này (khung, số, dấu, thứ tự); không tự suy luận, tự tính "
						+ "hay xếp hạng khác, không dùng tỷ trọng vật tư để xếp hạng. \"Hôm nay\" = tình hình tháng tính đến "
						+ "ngày có actual mới nhất (ngày ghi ở trên). % vượt ghi như \"(+25.3%)\" hoặc \"bằng 125.3% Fluct\", "
						+ "không ghi \"+125.3%\".",
				"Instruction: answer EXACTLY from this CONCLUSION (frames, numbers, signs, order); do not infer, "
						+ "compute or rank differently, do not rank by item share. \"Today\" = the month so far, up to the "
						+ "latest actual day (above). Write excess % as \"(+25.3%)\" or \"125.3% of Fluct\", never \"+125.3%\"."));

		sb.append(currentRate
				? t(lang,
				" Câu hỏi \"cứ chi như vậy\": dùng số theo tốc độ chi hiện tại và nói rõ \"theo tốc độ chi hiện tại\".",
				" \"At this rate\" question: use the current-rate numbers and say \"at the current spending rate\".")
				: t(lang,
				" \"Dự báo\" = dự báo chính (theo PL; chưa có PL thì theo nhịp Fluct); số \"theo tốc độ chi hiện tại\" chỉ nêu đúng như ghi chú, 1 câu ngắn.",
				" \"Forecast\" = the main forecast (based on PL; Fluct pace when no PL); mention \"at the current spending rate\" numbers only as "
						+ "noted, in 1 short sentence."));

		if (anyForecastOver) {
			sb.append(t(lang,
					" Có khung dự báo / đã vượt: KHÔNG được nói \"không có bất thường\" / \"không vượt\"; nêu các khung đó trước.",
					" Some frames are over / forecast over: do NOT say \"nothing unusual\" / \"not exceeding\"; list them first."));
		}

		if (ranking) {
			sb.append(t(lang,
					" Câu đầu nêu khung đứng đầu theo % Fluct; nếu khung vượt nhiều nhất theo K$ khác thì nêu thêm 1 câu; "
							+ "rồi mỗi khung 1 dòng theo đúng thứ tự.",
					" First sentence: the top frame by % of Fluct; if the largest excess in K$ is a different frame, add 1 "
							+ "sentence; then one line per frame in this order."));
		} else if (one) {
			sb.append(t(lang,
					" Câu đầu: kết luận vượt / không vượt, BẮT BUỘC nêu CẢ 2 mốc Fluct và FC (kể cả khi chỉ hỏi 1 mốc), "
							+ "chép nguyên văn: ",
					" First sentence: above / not above, MUST state BOTH Fluct and FC (even if only one was asked), "
							+ "copy verbatim: "))
					.append(mandatoryPhrases(frames, closed, currentRate, lang))
					.append(t(lang, ". Tối đa 3 câu.", ". At most 3 sentences."));
		} else {
			sb.append(t(lang, " Câu mở đầu PHẢI là: \"", " The opening sentence MUST be: \""))
					.append(openingSentence(frames, closed, currentRate, lang))
					.append("\".")
					.append(t(lang,
							" Rồi mỗi khung 1 dòng (tên, cuối tháng, vượt Fluct K$ và %, vượt FC K$) chép từ KẾT LUẬN; tối đa "
									+ "1 câu kết. Không bảng, không nhắc lại số đã nêu, không nêu nhóm không có khung.",
							" Then one line per frame (name, month-end, over Fluct K$ and %, over FC K$) copied from the "
									+ "CONCLUSION; at most 1 closing sentence. No table, no repeated numbers, do not mention "
									+ "empty groups."));
		}

		return sb.append('\n').toString();
	}

	// Câu mở đầu cố định theo số khung (code đếm), vd "Có 4 khung dự báo vượt Fluct (theo PL):"
	static String openingSentence(List<Frame> frames, boolean closed, boolean currentRate, String lang) {

		if (closed) {
			long over = frames.stream().filter(f -> f.a().alreadyOver()).count();
			return over > 0
					? t(lang, "Tháng đã kết thúc, có " + over + " khung vượt Fluct:",
					"Month closed, " + over + " frame(s) above Fluct:")
					: t(lang, "Tháng đã kết thúc, mọi khung trong Fluct.", "Month closed, all frames within Fluct.");
		}

		long forecastOver;
		long overFcOnly;
		long already = frames.stream().filter(f -> f.a().alreadyOver()).count();

		if (currentRate) {
			forecastOver = frames.stream().filter(f -> positive(f.a().projectedOver())).count();
			overFcOnly = frames.stream()
					.filter(f -> !positive(f.a().projectedOver()) && positive(f.a().projectedOverFc())).count();
		} else {
			forecastOver = frames.stream()
					.filter(f -> CostMonitoringAssessment.WILL_EXCEED.equals(f.a().mainStatus())).count();
			overFcOnly = frames.stream()
					.filter(f -> CostMonitoringAssessment.WITHIN.equals(f.a().mainStatus())
							&& positive(f.a().estimateOverFc())).count();
		}

		if (forecastOver == 0 && overFcOnly == 0 && already == 0) {
			return currentRate
					? t(lang, "Theo tốc độ chi hiện tại, không khung nào dự báo cuối tháng vượt Fluct hay FC.",
					"At the current spending rate, no frame is projected above Fluct or FC at month-end.")
					: t(lang, "Không khung nào dự báo cuối tháng vượt Fluct hay FC.",
					"No frame is forecast above Fluct or FC at month-end.");
		}

		List<String> parts = new ArrayList<>();

		if (forecastOver > 0) {
			parts.add(currentRate
					? t(lang, forecastOver + " khung dự báo vượt Fluct theo tốc độ chi hiện tại",
					forecastOver + " frame(s) projected above Fluct at the current spending rate")
					: t(lang, forecastOver + " khung dự báo vượt Fluct", forecastOver + " frame(s) forecast above Fluct")
					+ baseLabel(frames, lang));
		}
		if (overFcOnly > 0) {
			parts.add(t(lang, overFcOnly + " khung dự báo vượt FC nhưng trong Fluct",
					overFcOnly + " frame(s) forecast above FC but within Fluct"));
		}
		// Cả câu "cứ chi như vậy": khung đã vượt thực tế vẫn được liệt kê (appendAlreadyOver) -> đếm luôn
		if (already > 0) {
			parts.add(t(lang, already + " khung đã vượt Fluct thực tế", already + " frame(s) already above Fluct"));
		}

		return t(lang, "Có ", "There are ") + String.join(", ", parts) + ":";
	}

	// " (theo PL)" nếu mọi khung có dự báo theo PL; " (theo FC, chưa có PL)" nếu mọi khung theo FC;
	// " (theo nhịp Fluct)" nếu mọi khung theo Fluct; trộn -> " (dự báo chính)"
	static String baseLabel(List<Frame> frames, String lang) {

		boolean anyPl = frames.stream().anyMatch(f -> "PL".equals(f.a().estimateBase()));
		boolean anyFc = frames.stream().anyMatch(f -> "FC".equals(f.a().estimateBase()));
		boolean anyFluct = frames.stream().anyMatch(f -> "Fluct".equals(f.a().estimateBase()));

		if (anyPl && !anyFc && !anyFluct) {
			return t(lang, " (theo PL)", " (based on PL)");
		}
		if (anyFc && !anyPl && !anyFluct) {
			return t(lang, " (theo FC, chưa có PL)", " (based on FC, no PL yet)");
		}
		if (anyFluct && !anyPl && !anyFc) {
			return t(lang, " (theo nhịp Fluct)", " (based on Fluct pace)");
		}
		return t(lang, " (dự báo chính)", " (main forecast)");
	}

	// Cụm bắt buộc cho câu dự báo 1 khung: «PRESS T&SL: vượt Fluct +23.5 K$ (+30.1%); vượt FC +18.3 K$»
	private static String mandatoryPhrases(List<Frame> frames, boolean closed, boolean currentRate, String lang) {

		List<String> parts = new ArrayList<>();

		for (Frame f : frames) {

			CostMonitoringAssessment a = f.a();

			String fluct;
			String fc;

			if (closed) {
				fluct = CmText.versus("Fluct", CmText.subR1(a.act(), a.fluct()), null, lang);
				fc = CmText.versus("FC", CmText.subR1(a.act(), a.fc()), null, lang);
			} else if (currentRate) {
				fluct = CmText.versus("Fluct", a.projectedOver(), a.projectedOverPct(), lang);
				fc = CmText.versus("FC", a.projectedOverFc(), null, lang);
			} else {
				fluct = CmText.versus("Fluct", a.estimateOver(), a.estimateOverPct(), lang);
				fc = CmText.versus("FC", a.estimateOverFc(), null, lang);
			}

			parts.add("«" + f.name() + ": " + fluct + "; " + fc + "»");
		}

		return String.join(", ", parts);
	}

	static boolean positive(Double value) {
		// Số đã làm tròn 1 chữ số (signedK) > 0
		return value != null && Math.round(value * 10) > 0;
	}
}
