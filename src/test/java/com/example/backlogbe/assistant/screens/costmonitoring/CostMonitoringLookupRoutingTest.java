package com.example.backlogbe.assistant.screens.costmonitoring;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Định tuyến các câu hỏi trong audit log 2026-11-09
class CostMonitoringLookupRoutingTest {

	private static final Map<String, String> PARAMS = Map.of("month", "2026-11");

	private static String kind(String question) {
		return CostMonitoringLookupAnswers.kind(question, CostMonitoringIntentClassifier.fold(question));
	}

	@Test
	void topItemQuestions() {
		assertEquals(CostMonitoringLookupAnswers.TOP_ITEM, kind("vật tư lớn trong press t&sl là gì"));
		assertEquals(CostMonitoringLookupAnswers.TOP_ITEM, kind("mã vật tư nào là mắc nhất ?"));
		assertEquals(CostMonitoringLookupAnswers.TOP_ITEM, kind("Vật tư nào chi nhiều nhất ở MOLD T&SL?"));
	}

	@Test
	void topDayQuestion() {
		assertEquals(CostMonitoringLookupAnswers.TOP_DAY, kind("Ngày nào MOLD T&SL chi nhiều nhất?"));
	}

	@Test
	void remainingBudgetQuestion() {
		assertEquals(CostMonitoringLookupAnswers.REMAINING_BUDGET,
				kind("Mỗi ngày còn lại được chi tối đa bao nhiêu để không vượt target?"));
	}

	@Test
	void anomalyTodayQuestion() {
		assertEquals(CostMonitoringLookupAnswers.ANOMALY_TODAY, kind("hôm nay có data nào bất thường ko ?"));
	}

	@Test
	void notLookup() {
		assertNull(kind("Cứ chi như vậy thì PRESS T&SL có vượt FC không?"));
		assertNull(kind("Có gì bất thường trong tháng này?"));
		// "mấy" (bao nhiêu) không phải "máy" -> câu hỏi số ngày còn lại, không phải top máy
		assertEquals(CostMonitoringLookupAnswers.REMAINING_DAYS, kind("tháng này còn mấy ngày?"));
	}

	// Audit log 2026-11-10: các câu này qua LLM trả lời sai (tên khung thay cho máy, cộng cost center T&SL + RM)
	@Test
	void topDetailQuestions() {
		assertEquals(CostMonitoringLookupAnswers.TOP_DETAIL, kind("Máy nào chi nhiều nhất?"));
		assertEquals(CostMonitoringLookupAnswers.TOP_DETAIL, kind("Máy nào chi nhiều nhất ở PRESS T&SL?"));
		assertEquals(CostMonitoringLookupAnswers.TOP_DETAIL, kind("cost center nào tốn nhất của guide"));
		assertEquals(CostMonitoringLookupAnswers.TOP_DETAIL, kind("Category nào lớn nhất ở MOLD RM?"));
		assertEquals(CostMonitoringLookupAnswers.TOP_DETAIL, kind("loại vật tư nào chi nhiều nhất"));
		assertEquals(CostMonitoringLookupAnswers.TOP_DETAIL, kind("which machine spends the most"));

		assertEquals(CostMonitoringLookupAnswers.Dimension.MACHINE, dimension("Máy nào chi nhiều nhất?"));
		assertEquals(CostMonitoringLookupAnswers.Dimension.COST_CENTER, dimension("cost center nào tốn nhất của guide"));
		assertEquals(CostMonitoringLookupAnswers.Dimension.CATEGORY, dimension("loại vật tư nào chi nhiều nhất"));
		assertNull(dimension("mấy vật tư lớn nhất"));
	}

	private static CostMonitoringLookupAnswers.Dimension dimension(String question) {
		return CostMonitoringLookupAnswers.dimension(question, CostMonitoringIntentClassifier.fold(question));
	}

	@Test
	void compactOverviewKeepsSummaryAndFirstGroup() {
		String overview = "Có 4 khung dự báo vượt Fluct, 1 khung dự báo vượt FC nhưng trong Fluct:\n\n"
				+ "**Dự báo vượt Fluct (4 khung):**\n- GUIDE T&SL: vượt Fluct +6.6 K$ (+11.1%)\n\n"
				+ "**Dự báo vượt FC nhưng trong Fluct (1 khung):**\n- PRESS RM: vượt FC +14.8 K$\n\n"
				+ "_Số liệu tháng 2026-11, actual đến ngày 9._";
		String compact = CostMonitoringAnswerGuide.compactOverview(overview, "vi");
		assertTrue(compact.contains("GUIDE T&SL"));
		assertTrue(compact.endsWith("_Số liệu tháng 2026-11, actual đến ngày 9._"));
		assertFalse(compact.contains("PRESS RM"));
		// Ít nhóm -> giữ nguyên
		String short1 = "Không khung nào vượt.\n\n_Số liệu._";
		assertEquals(short1, CostMonitoringAnswerGuide.compactOverview(short1, "vi"));
	}

	@Test
	void framesFromQuestion() {
		assertEquals(List.of(new CostMonitoringFocus("PRESS", "T&SL")),
				CostMonitoringLookupAnswers.frames("vật tư lớn trong press t&sl là gì", PARAMS));
		// Không nêu khung -> 3 bộ phận × T&SL, RM
		assertEquals(6, CostMonitoringLookupAnswers.frames("mã vật tư nào là mắc nhất ?", PARAMS).size());
		assertEquals(2, CostMonitoringLookupAnswers.frames("vật tư nào lớn nhất ở mold", PARAMS).size());
	}

	@Test
	void topItemsPrefetchForMachineQuestion() {
		assertTrue(TopItemsPrefetchRule.isTopItemsQuestion("Máy nào chi nhiều nhất ở PRESS T&SL?", PARAMS));
		assertTrue(TopItemsPrefetchRule.isTopItemsQuestion("cost center nào tốn nhất của guide", PARAMS));
		// Không xác định được bộ phận / hỏi nguyên nhân -> không pre-fetch
		assertFalse(TopItemsPrefetchRule.isTopItemsQuestion("Máy nào chi nhiều nhất?", PARAMS));
		assertFalse(TopItemsPrefetchRule.isTopItemsQuestion("Vì sao vật tư PRESS T&SL tăng?", PARAMS));
	}
}
