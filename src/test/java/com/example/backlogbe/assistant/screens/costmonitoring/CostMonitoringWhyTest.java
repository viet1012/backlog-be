package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.api.dto.AssistantCard;
import com.example.backlogbe.assistant.core.screen.AnswerGuide;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostTopItemsRepository;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// "Tại sao biết PRESS RM sẽ vượt" (đánh giá 2026-11-20): tách +58.3 = +41.0 target hạ + +17.3 vượt FC, nêu cách biết,
// tiến độ ghi rõ phép tính đến ngày actual của khung (RM 18), footer không ghi "actual đến ngày 19"
class CostMonitoringWhyTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

	// MTD tăng đều tới actEnd ở ngày lastDay, Estimate tăng đều tới estEom ở ngày 30
	private static CostChartDto chart(String div, String cost, int lastDay, double actEnd, double estEom,
									  double fluct, double fc, Double pl) {
		List<CostChartPointDto> points = new ArrayList<>();
		for (int day = 1; day <= 30; day++) {
			Double act = day <= lastDay ? actEnd * day / lastDay : null;
			Double est = day > lastDay ? actEnd + (estEom - actEnd) * (day - lastDay) / (30 - lastDay) : null;
			points.add(new CostChartPointDto(LocalDate.of(2026, 11, day), null, act, est, fluct));
		}
		return new CostChartDto(div, cost, fc, pl, null, actEnd, estEom, points, null);
	}

	@Test
	void splitsForecastOverrunIntoTargetCutAndOverFc() {
		CostMonitoringChartResponse response = new CostMonitoringChartResponse("2026-11", null, "K$", List.of(
				chart("PR", "RM", 18, 332.8, 525.5, 467.2, 508.2, 520.0),
				chart("PR", "T&SL", 19, 120.0, 194.5, 161.0, 175.2, 190.0)));
		CostMonitoringSnapshotLoader loader = mock(CostMonitoringSnapshotLoader.class);
		when(loader.load(any(Map.class))).thenReturn(CostMonitoringSnapshot.of(response, TODAY));
		when(loader.today()).thenReturn(TODAY);
		CostMonitoringLookupAnswers answers =
				new CostMonitoringLookupAnswers(loader, mock(CostTopItemsRepository.class), mock(CostWorkingDateRepository.class));

		AnswerGuide g = answers.why(new CostMonitoringFocus("PRESS", "RM"), Map.of("month", "2026-11"), "vi", "test");

		assertNotNull(g);
		String text = g.directAnswer();
		System.out.println(text);

		assertTrue(text.contains("dự báo cuối tháng vượt **+58.3 K$** = **+41.0 K$** do target bị hạ (Fluct thấp hơn FC) + **+17.3 K$** do dự báo vượt chính FC."), text);
		assertTrue(text.contains("- Cách biết: dự báo 525.5 K$ = Actual 332.8 K$ + 192.7 K$ cho 12 ngày còn lại (phân bổ PL TGT"), text);
		assertTrue(text.contains("ngân sách còn lại trong Fluct chỉ 134.4 K$ → thiếu +58.3 K$"), text);
		assertTrue(text.contains("- Tốc độ chi đến ngày 18: so với tiến độ FC (508.2 × 18/30 = 304.9) nhanh hơn +27.9 K$"), text);
		assertTrue(text.contains("so với tiến độ Fluct (467.2 × 18/30 = 280.3) nhanh hơn +52.5 K$"), text);
		assertFalse(text.contains("nguyên nhân chính"), text);
		// target hạ đã nêu ở câu đầu -> không lặp dòng "Fluct thấp hơn FC"
		assertFalse(text.contains("- Fluct thấp hơn FC"), text);
		assertTrue(text.endsWith("_Số liệu tháng 2026-11; RM actual đến ngày 18 (dữ liệu chung đến ngày 19)._"), text);

		// Thẻ tóm tắt: chip tách nguyên nhân + cầu nối Fluct → FC → Dự báo
		CostMonitoringChartBuilder builder = new CostMonitoringChartBuilder(loader);
		AssistantCard card = builder.buildCard(CostMonitoringLookupAnswers.WHY_FRAME, "Tại sao biết PRESS RM sẽ vượt?",
				Map.of("month", "2026-11"), "vi");
		assertNotNull(card);
		assertEquals(AssistantCard.OVER, card.tone());
		assertEquals("PRESS RM", card.title());
		assertEquals("Dự báo cuối tháng vượt Fluct **+58.3 K$**", card.headline());
		assertEquals(List.of("Target bị hạ", "Vượt FC", "Đang chi"), card.chips().stream().map(AssistantCard.Chip::label).toList());
		assertEquals("+41.0 K$", card.chips().get(0).value());
		assertEquals("+17.3 K$", card.chips().get(1).value());
		assertEquals(List.of(467.2, 41.0, 508.2, 17.3, 525.5), card.bridge().stream().map(AssistantCard.Step::value).toList());
		assertEquals(332.8, card.budget().actual(), 1e-9);
		assertEquals("Actual đến ngày 18", card.budget().actualLabel());
	}
}
