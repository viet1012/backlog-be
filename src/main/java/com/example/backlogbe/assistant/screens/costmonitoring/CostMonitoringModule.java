package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.prefetch.PrefetchRule;
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
// Params : month (yyyy-MM | yyyy.MM, trống = tháng hiện tại), simuWeek / plTgtMode (Simu_W1..Simu_W5).
// Context: CostMonitoringContextProvider (đầy đủ / rút gọn cho tháng được hỏi).
// Tool   : get_daily_series, get_top_items, compare_previous_month, explain_variance, get_ratio_trend
//          (thứ tự giữ nguyên khi gửi cho LLM).
// Prefetch: RatioTrendPrefetchRule.
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
			ParamSpec.of("plTgtMode", SIMU_WEEK_REGEX, "Tên khác của simuWeek")
	);

	private final CostMonitoringContextProvider contextProvider;
	private final CostMonitoringSuggestionConditions suggestionConditions;
	private final List<AssistantTool> tools;
	private final List<PrefetchRule> prefetchRules;

	public CostMonitoringModule(
			CostMonitoringContextProvider contextProvider,
			CostMonitoringSuggestionConditions suggestionConditions,
			DailySeriesTool dailySeriesTool,
			TopItemsTool topItemsTool,
			ComparePreviousMonthTool comparePreviousMonthTool,
			ExplainVarianceTool explainVarianceTool,
			RatioTrendTool ratioTrendTool,
			RatioTrendPrefetchRule ratioTrendPrefetchRule
	) {
		this.contextProvider = contextProvider;
		this.suggestionConditions = suggestionConditions;
		this.tools = List.of(dailySeriesTool, topItemsTool, comparePreviousMonthTool, explainVarianceTool,
				ratioTrendTool);
		this.prefetchRules = List.of(ratioTrendPrefetchRule);
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
	public List<AssistantTool> tools() {
		return tools;
	}

	@Override
	public List<PrefetchRule> prefetchRules() {
		return prefetchRules;
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
