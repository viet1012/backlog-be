package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.api.dto.ToolCall;
import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringIntentClassifier.Result;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringIntentClassifier.Signal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// =========================================================
// PRE-FETCH explain_variance (để model trả lời ngay trong 1 lần gọi LLM) + KẾ HOẠCH theo intent
//
// Bỏ qua: câu hỏi khái niệm ("là gì", "cách tính"…) và câu hỏi xu hướng Fluct (RatioTrendPrefetchRule lo).
// Intent: CostMonitoringIntentClassifier (câu hỏi đã bỏ dấu; ưu tiên WHY > COMPARE > RANKING > FORECAST > OVERVIEW):
//   WHY      : xác định được đúng 1 khung -> WHY, 1 lần gọi; không xác định được khung -> xét intent kế tiếp.
//   COMPARE  : khung = bộ phận nêu trong câu (thiếu -> ô đang xem, vẫn thiếu -> cả 3) × loại chi phí (thiếu -> ô đang
//              xem, vẫn thiếu -> T&SL và RM). ≤ 2 khung -> mỗi khung 1 lần gọi (đầy đủ); nhiều hơn -> div=ALL tóm tắt.
//   RANKING  : div=ALL tóm tắt, cost: từ câu hỏi; thiếu -> T&SL và RM.
//   FORECAST : div: từ câu hỏi; thiếu -> ô đang xem; vẫn thiếu hoặc ≥ 2 bộ phận -> ALL (tóm tắt);
//              cost: từ câu hỏi; thiếu -> ô đang xem; vẫn thiếu -> T&SL và RM -> mỗi cost 1 lần gọi.
//   OVERVIEW : như RANKING.
//   Không khớp intent nào (KHÔNG mặc định overview):
//     phân tích đúng 1 khung (đánh giá, phân tích, rủi ro, tình hình, thế nào, ổn không…) hoặc câu hỏi từ drawer
//     (trừ câu hỏi theo ngày – DailySeriesPrefetchRule lo) -> FRAME, 1 lần gọi; còn lại -> không pre-fetch.
// Không ra kết quả -> fallbackHint nhắc model tự gọi explain_variance.
// =========================================================
@Component
public class ExplainVariancePrefetchRule implements PrefetchRule {

	public static final String TOOL = "explain_variance";

	// Không nêu loại chi phí -> 2 loại có actual
	private static final List<String> DEFAULT_COSTS = List.of("T&SL", "RM");

	// Nhãn tín hiệu COMPARE khi chỉ do nêu ≥ 2 khung (CostMonitoringIntentClassifier)
	private static final String COMPARE_BY_FRAMES = ">=2 khung";

	// So sánh ≤ mức này khung -> pre-fetch từng khung (bản đầy đủ); nhiều hơn -> div=ALL tóm tắt
	private static final int COMPARE_FULL_MAX = 2;

	// Câu hỏi phân tích / đánh giá 1 khung (không dấu, khớp trên câu đã bỏ dấu)
	private static final List<String> ANALYSIS_KEYWORDS = List.of(
			"danh gia", "phan tich", "rui ro", "tinh hinh", "the nao", "chap nhan",
			"why", "evaluate", "assess", "analy", "risk", "how is", "status"
	);

	// Từ đứng riêng (câu đã bỏ dấu): "sao", "ntn", "ổn ko / ổn không / có ổn" (vd "sao rm mold vượt z",
	// "press tsl có ổn ko"); không khớp "on" đứng một mình (tiếng Anh "on pace")
	private static final Pattern ANALYSIS_WORDS =
			CostMonitoringQuestion.word("sao|ntn|on\\s+(?:ko|khong|k|chua|hong)|co\\s+on");

	private static final String HINT_VI =
			"(Lưu ý hệ thống: gọi explain_variance cho khung được hỏi trước khi trả lời; câu hỏi dự báo trả lời theo \"Dự báo cuối tháng (chính)\", câu \"cứ chi như vậy\" theo tốc độ chi hiện tại.)";
	private static final String HINT_EN =
			"(System note: call explain_variance for the asked frame before answering; for forecast questions use the \"Month-end forecast (main)\", for \"at this rate\" questions use the current spending rate.)";

	@Override
	public boolean matches(String question, String screenKey) {
		return matches(question, screenKey, Map.of());
	}

	@Override
	public boolean matches(String question, String screenKey, Map<String, String> params) {
		return CostMonitoringModule.SCREEN_KEY.equals(screenKey) && !calls(question, params).isEmpty();
	}

	@Override
	public List<ToolCall> calls(String question, Map<String, String> params) {

		Plan plan = plan(question, params);

		if (plan == null) {
			return List.of();
		}

		// So sánh ít khung: từng khung bản đầy đủ
		if (plan.intent() == Intent.COMPARE && plan.frames().size() <= COMPARE_FULL_MAX) {
			List<ToolCall> calls = new ArrayList<>();
			int i = 0;
			for (CostMonitoringFocus f : plan.frames()) {
				calls.add(call(++i, f.div(), f.cost(), false));
			}
			return calls;
		}

		return toCalls(plan.div(), plan.costs(), plan.summary());
	}

	// Loại câu hỏi theo cách pre-fetch (dùng chung với CostMonitoringAnswerGuide)
	enum Intent {
		// Dự báo, không nêu / nêu ≥ 2 bộ phận (div=ALL)
		FORECAST_ALL,
		// Dự báo đúng 1 bộ phận (từ câu hỏi hoặc ô drawer)
		FORECAST_ONE,
		// "… nhất", "xếp hạng"
		RANKING,
		// Bất thường / cần theo dõi / cần chú ý / đáng lo
		OVERVIEW,
		// So sánh các khung (≥ 2 khung, "so sánh", "vs", "với", "chart nào", "bên nào", "tốt hơn", "tệ hơn")
		COMPARE,
		// "Vì sao / tại sao / nguyên nhân …" về đúng 1 khung (CostMonitoringAnswerGuide dựng khối nguyên nhân)
		WHY,
		// Phân tích 1 khung hoặc câu hỏi từ drawer
		FRAME
	}

	/*
	 * Kế hoạch pre-fetch: div (hoặc ALL), costs (mỗi cost 1 lần gọi), summary (chế độ tóm tắt cho ALL),
	 * atCurrentRate (câu dự báo "cứ chi như vậy…": theo tốc độ chi hiện tại thay vì PL Estimate),
	 * frames (COMPARE: các khung được so; còn lại rỗng), match (kết quả phân loại – audit intentMatch, certain).
	 */
	record Plan(
			Intent intent,
			String div,
			List<String> costs,
			boolean summary,
			boolean atCurrentRate,
			List<CostMonitoringFocus> frames,
			Result match
	) {

		Plan(Intent intent, String div, List<String> costs, boolean summary, boolean atCurrentRate, Result match) {
			this(intent, div, costs, summary, atCurrentRate, List.of(), match);
		}
	}

	// Câu hỏi dự báo theo tốc độ chi hiện tại ("cứ chi như vậy", "giữ tốc độ", "at this rate"…)
	public static boolean isCurrentRateQuestion(String question) {
		return question != null && CostMonitoringIntentClassifier.classify(question, null).currentRate();
	}

	// Phân loại câu hỏi (dùng cho audit khi không có kế hoạch); câu khái niệm / xu hướng Fluct -> null
	static Result classify(String question, Map<String, String> params) {

		if (question == null || question.isBlank()) {
			return null;
		}

		String q = CostMonitoringQuestion.normalize(question);

		if (CostMonitoringQuestion.isConcept(q) || RatioTrendPrefetchRule.isFluctTrendQuestion(question)) {
			return null;
		}

		return CostMonitoringIntentClassifier.classify(question, CostMonitoringFocus.from(params != null ? params : Map.of()));
	}

	// null = không pre-fetch
	static Plan plan(String question, Map<String, String> params) {

		Result r = classify(question, params);

		if (r == null) {
			return null;
		}

		Map<String, String> safeParams = params != null ? params : Map.of();
		CostMonitoringFocus focus = CostMonitoringFocus.from(safeParams);

		for (Signal signal : List.of(Signal.WHY, Signal.COMPARE, Signal.RANKING, Signal.FORECAST, Signal.OVERVIEW)) {

			if (!r.has(signal)) {
				continue;
			}

			switch (signal) {

				case WHY -> {
					// Nguyên nhân về đúng 1 khung (kể cả "Vì sao PRESS T&SL dự báo vượt?"); không có khung -> intent kế tiếp
					CostMonitoringFocus frame = CostMonitoringQuestion.frame(question, safeParams);
					if (frame != null) {
						return new Plan(Intent.WHY, frame.div(), List.of(frame.cost()), false, false, r);
					}
				}

				case COMPARE -> {
					// "So sánh" chỉ vì nêu ≥ 2 khung (không có từ so sánh) + câu hỏi theo ngày / khi nào vượt:
					// get_daily_series (DailySeriesPrefetchRule) đủ trả lời. Thêm explain_variance bản đầy đủ từng khung
					// làm prompt ~17–18k token -> LLM HTTP 400 (test 2026-11-10 ext-88 / ext-89).
					if (r.matched().get(Signal.COMPARE).equals(List.of(COMPARE_BY_FRAMES))
							&& DailySeriesPrefetchRule.isDailyQuestion(question)) {
						return null;
					}
					List<CostMonitoringFocus> frames = compareFrames(r, focus);
					List<String> costs = frames.stream().map(CostMonitoringFocus::cost).distinct().toList();
					return new Plan(Intent.COMPARE, CostMonitoringToolArgs.ALL, costs, true, false, frames, r);
				}

				case RANKING, OVERVIEW -> {
					// Chế độ tóm tắt: mỗi khung 1 dòng + xếp hạng, ≤ 1200 ký tự / loại chi phí (tổng ≈ 1.5k token)
					return new Plan(signal == Signal.RANKING ? Intent.RANKING : Intent.OVERVIEW,
							CostMonitoringToolArgs.ALL, r.costs().isEmpty() ? DEFAULT_COSTS : r.costs(), true, false, r);
				}

				case FORECAST -> {
					List<String> divs = r.divs();
					List<String> costs = r.costs();

					if (divs.isEmpty() && focus != null) {
						divs = List.of(focus.div());
					}
					if (costs.isEmpty()) {
						costs = focus != null ? List.of(focus.cost()) : DEFAULT_COSTS;
					}

					// Không nêu / nêu ≥ 2 bộ phận -> div=ALL chế độ tóm tắt
					return divs.size() == 1
							? new Plan(Intent.FORECAST_ONE, divs.get(0), costs, false, r.currentRate(), r)
							: new Plan(Intent.FORECAST_ALL, CostMonitoringToolArgs.ALL, costs, true, r.currentRate(), r);
				}
			}
		}

		// Không khớp intent nào: phân tích đúng 1 khung / câu hỏi từ drawer (trừ câu hỏi theo ngày)
		CostMonitoringFocus frame = CostMonitoringQuestion.frame(question, safeParams);

		if (frame == null) {
			return null;
		}

		String folded = CostMonitoringIntentClassifier.fold(question);
		boolean analysis = isAnalysis(folded) || itemChangeVsPreviousMonth(folded);
		boolean drawer = focus != null && !DailySeriesPrefetchRule.isDailyQuestion(question);

		if (!analysis && !drawer) {
			return null;
		}

		return new Plan(Intent.FRAME, frame.div(), List.of(frame.cost()), false, false, r);
	}

	// "Vật tư / Category nào làm tăng / giảm so với tháng trước": explain_variance có so cùng kỳ theo Category và vật tư
	// (audit 2026-11-10: model gọi compare_previous_month + get_top_items rồi kết luận "không có vật tư nào tăng")
	private static final java.util.regex.Pattern ITEM_WORD = CostMonitoringQuestion.word(
			"vat tu|ma vat tu|category|loai vat tu|item|items|material");
	private static final java.util.regex.Pattern CHANGE_WORD = CostMonitoringQuestion.word(
			"tang|giam|lam tang|lam giam|increase|decrease|rise|drop|higher|lower");
	private static final java.util.regex.Pattern PREV_WORD = CostMonitoringQuestion.word(
			"thang truoc|cung ky|last month|previous month");

	static boolean itemChangeVsPreviousMonth(String folded) {
		return ITEM_WORD.matcher(folded).find() && CHANGE_WORD.matcher(folded).find()
				&& PREV_WORD.matcher(folded).find();
	}

	// Khung được so: bộ phận (câu hỏi -> ô đang xem -> cả 3) × loại chi phí (câu hỏi -> ô đang xem -> T&SL, RM)
	private static List<CostMonitoringFocus> compareFrames(Result r, CostMonitoringFocus focus) {

		List<String> divs = !r.divs().isEmpty() ? r.divs()
				: focus != null && r.costs().size() >= 2 ? List.of(focus.div())
				: CostMonitoringSnapshot.DIVS;
		List<String> costs = !r.costs().isEmpty() ? r.costs()
				: focus != null ? List.of(focus.cost())
				: DEFAULT_COSTS;

		List<CostMonitoringFocus> frames = new ArrayList<>();

		for (String cost : costs) {
			for (String div : divs) {
				frames.add(new CostMonitoringFocus(div, cost));
			}
		}

		return frames;
	}

	@Override
	public String fallbackHint(String lang) {
		return CmText.isEn(lang) ? HINT_EN : HINT_VI;
	}

	public static boolean isForecastQuestion(String question) {
		Result r = classify(question, Map.of());
		return r != null && r.has(Signal.FORECAST);
	}

	// folded: câu đã bỏ dấu (CostMonitoringIntentClassifier.fold)
	private static boolean isAnalysis(String folded) {
		return CostMonitoringQuestion.containsAny(folded, ANALYSIS_KEYWORDS) || ANALYSIS_WORDS.matcher(folded).find();
	}

	// summary = true: thêm tham số ẩn summary của explain_variance (chỉ có tác dụng với div=ALL)
	private static List<ToolCall> toCalls(String div, List<String> costs, boolean summary) {

		List<ToolCall> calls = new ArrayList<>();
		int i = 0;

		for (String cost : costs) {
			calls.add(call(++i, div, cost, summary));
		}

		return calls;
	}

	private static ToolCall call(int index, String div, String cost, boolean summary) {

		String extra = summary ? ",\"" + ExplainVarianceTool.ARG_SUMMARY + "\":true" : "";

		return new ToolCall(
				"prefetch-" + TOOL + "-" + index,
				TOOL,
				"{\"div\":\"" + div + "\",\"cost\":\"" + cost + "\"" + extra + "}"
		);
	}
}
