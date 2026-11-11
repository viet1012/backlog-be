package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
import com.example.backlogbe.assistant.core.screen.AnswerGuide;
import com.example.backlogbe.assistant.core.screen.ParamSpec;
import com.example.backlogbe.assistant.core.screen.ScreenModule;
import com.example.backlogbe.assistant.core.suggestion.SuggestionCondition;
import com.example.backlogbe.assistant.core.tool.AssistantTool;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// =========================================================
// MÀN HÌNH COST MONITORING
//
// Params : month (yyyy-MM | yyyy.MM, trống = tháng hiện tại), simuWeek / plTgtMode (Simu_W1..Simu_W5),
//          factory + category (ô đang mở từ drawer, tùy chọn – CostMonitoringFocus).
// Context: CostMonitoringContextProvider (đầy đủ / rút gọn cho tháng được hỏi).
// Tool   : get_daily_series, get_top_items, compare_previous_month, explain_variance, get_ratio_trend,
//          get_item_insight (thứ tự giữ nguyên khi gửi cho LLM).
// Prefetch: RatioTrendPrefetchRule (get_ratio_trend), ExplainVariancePrefetchRule (explain_variance cho câu hỏi
//          dự báo, câu hỏi phân tích 1 khung, câu hỏi từ drawer), DailySeriesPrefetchRule (get_daily_series cho câu
//          hỏi theo ngày về 1 khung), TopItemsPrefetchRule (get_top_items cho câu hỏi vật tư / máy / cost center).
// Tra cứu template: CostMonitoringLookupAnswers (top vật tư, ngày chi nhiều nhất, ngân sách mỗi ngày, bất thường
//          hôm nay) – chạy trước phân loại intent của CostMonitoringAnswerGuide.
// Context: câu hỏi xác định được đúng 1 khung -> chỉ gửi khung đó (CostMonitoringContextProvider).
// AnswerGuide: CostMonitoringAnswerGuide (khối KẾT LUẬN cho câu hỏi tổng quan / xếp hạng / dự báo chung;
//          pre-fetch đủ -> tool_choice none) + câu trả lời template không gọi LLM (CostMonitoringAnswerTemplate)
//          cho tổng quan / xếp hạng / dự báo.
// Gợi ý  : CostMonitoringSuggestionConditions.
// Tháng khác: hỗ trợ; tool chạy cho tháng khác thì bỏ simuWeek / plTgtMode (tuần Simu mới nhất).
// Văn bản: resources/assistant/screens/cost-monitoring/{knowledge.md, rules.md, codes.json,
//          suggestions.json, eval.json}
// =========================================================
@Component
public class CostMonitoringModule implements ScreenModule {

	public static final String SCREEN_KEY = "cost-monitoring";

	private static final String SIMU_WEEK_REGEX = "^Simu_W[1-5]$";

	private static final List<ParamSpec> PARAMS = List.of(
			ParamSpec.of("month", "^\\d{4}[-.]\\d{2}$", "Tháng đang xem yyyy-MM hoặc yyyy.MM; trống = tháng hiện tại"),
			ParamSpec.of("simuWeek", SIMU_WEEK_REGEX, "Tuần PL simulation; trống = tuần mới nhất có số"),
			ParamSpec.of("plTgtMode", SIMU_WEEK_REGEX, "Tên khác của simuWeek"),
			// Mở chat từ drawer của 1 ô: tool mặc định tra đúng ô này (CostMonitoringFocus)
			ParamSpec.of(CostMonitoringFocus.PARAM_FACTORY, "(?i)^(PRESS|MOLD|GUIDE|PR|MO|GU)$",
					"Bộ phận của ô đang mở (drawer); trống = không có"),
			ParamSpec.of(CostMonitoringFocus.PARAM_CATEGORY, "(?i)^(OT|T&SL|TSL|RM)$",
					"Loại chi phí của ô đang mở (drawer); trống = không có"),
			ParamSpec.of(CostMonitoringFocus.PARAM_DIV, "(?i)^(PRESS|MOLD|GUIDE|PR|MO|GU)$",
					"Tên khác của factory"),
			ParamSpec.of(CostMonitoringFocus.PARAM_COST, "(?i)^(OT|T&SL|TSL|RM)$",
					"Tên khác của category")
	);

	private final CostMonitoringContextProvider contextProvider;
	private final CostMonitoringSuggestionConditions suggestionConditions;
	private final List<AssistantTool> tools;
	private final List<PrefetchRule> prefetchRules;
	private final CostMonitoringAnswerGuide answerGuide;

	public CostMonitoringModule(
			CostMonitoringContextProvider contextProvider,
			CostMonitoringSuggestionConditions suggestionConditions,
			DailySeriesTool dailySeriesTool,
			TopItemsTool topItemsTool,
			ComparePreviousMonthTool comparePreviousMonthTool,
			ExplainVarianceTool explainVarianceTool,
			RatioTrendTool ratioTrendTool,
			ItemInsightTool itemInsightTool,
			RatioTrendPrefetchRule ratioTrendPrefetchRule,
			ExplainVariancePrefetchRule explainVariancePrefetchRule,
			DailySeriesPrefetchRule dailySeriesPrefetchRule,
			TopItemsPrefetchRule topItemsPrefetchRule,
			CostMonitoringAnswerGuide answerGuide
	) {
		this.answerGuide = answerGuide;
		this.contextProvider = contextProvider;
		this.suggestionConditions = suggestionConditions;
		this.tools = List.of(dailySeriesTool, topItemsTool, comparePreviousMonthTool, explainVarianceTool,
				ratioTrendTool, itemInsightTool);
		this.prefetchRules = List.of(ratioTrendPrefetchRule, explainVariancePrefetchRule, dailySeriesPrefetchRule,
				topItemsPrefetchRule);
	}

	@Override
	public String screenKey() {
		return SCREEN_KEY;
	}

	@Override
	public List<ParamSpec> paramSpec() {
		return PARAMS;
	}

	@Override
	public String buildContext(Map<String, String> params, String lang) {
		return contextProvider.buildContext(params, lang);
	}

	@Override
	public String buildContext(Map<String, String> params, String lang, String question) {
		return contextProvider.buildContext(params, lang, question);
	}

	@Override
	public List<AssistantTool> tools() {
		return tools;
	}

	@Override
	public List<PrefetchRule> prefetchRules() {
		return prefetchRules;
	}

	// Khối KẾT LUẬN + cấm gọi thêm tool cho câu hỏi tổng quan / xếp hạng / dự báo (CostMonitoringAnswerGuide)
	@Override
	public AnswerGuide answerGuide(String question, Map<String, String> params, String lang) {
		return answerGuide.guide(question, params, lang);
	}

	// "Tháng 9 có gì bất thường?" -> template tổng quan / dự báo của tháng đó (test 2026-11-10 ext-17 / ext-68: 35–48 s qua LLM)
	@Override
	public AnswerGuide answerGuideForOtherMonth(String question, Map<String, String> params, String lang) {
		return answerGuide.otherMonth(question, params, lang);
	}

	@Override
	public List<SuggestionCondition> suggestionConditions(Map<String, String> params) {
		return suggestionConditions.conditions(params);
	}

	@Override
	public String suggestionGreeting(List<SuggestionCondition> conditions, String lang) {
		return suggestionConditions.greeting(conditions, lang);
	}

	@Override
	public boolean supportsMonthQuestions() {
		return true;
	}

	@Override
	public String buildCompactContext(YearMonth month, String lang) {
		return contextProvider.buildCompactContext(month, lang);
	}

	// Tháng khác: PL TGT theo tuần Simu mới nhất của tháng đó (bỏ simuWeek / plTgtMode)
	@Override
	public Map<String, String> paramsForMonth(Map<String, String> params, YearMonth month) {

		Map<String, String> result = new HashMap<>(params);
		result.put("month", month.toString());
		result.remove("simuWeek");
		result.remove("plTgtMode");

		return result;
	}
}
