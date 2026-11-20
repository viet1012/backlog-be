package com.example.backlogbe.assistant.screens.costmonitoring;

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

// "Hôm nay có gì bất thường" (đánh giá 2026-11-20): kết luận dự báo vượt lên đầu, cảnh báo OT cũ, chi gần nhất gộp theo loại
class CostMonitoringAnomalyTodayTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

	// Chi đều perDay đến ngày lastDay (ngày cuối chi lastAmount), Estimate theo perDay × 1.1 đến cuối tháng
	private static CostChartDto chart(String div, String cost, int lastDay, double perDay, double lastAmount, double fluct) {
		List<CostChartPointDto> points = new ArrayList<>();
		double mtd = 0;
		for (int day = 1; day <= 30; day++) {
			Double act = null;
			Double est = null;
			if (day <= lastDay) {
				mtd += day == lastDay ? lastAmount : perDay;
				act = mtd;
			} else {
				est = mtd + perDay * 1.1 * (day - lastDay);
			}
			points.add(new CostChartPointDto(LocalDate.of(2026, 11, day), null, act, est, fluct));
		}
		double estEom = mtd + perDay * 1.1 * (30 - lastDay);
		return new CostChartDto(div, cost, fluct, fluct, null, mtd, estEom, points, null);
	}

	private static CostMonitoringLookupAnswers answers() {
		CostMonitoringChartResponse response = new CostMonitoringChartResponse("2026-11", null, "K$", List.of(
				chart("PR", "OT", 10, 2.7, 3.1, 47.2),
				chart("MO", "OT", 10, 0.7, 0.8, 30.0),
				chart("GUIDE", "OT", 10, 1.2, 1.5, 30.8),
				chart("PR", "T&SL", 19, 8.5, 5.4, 161.0),
				chart("MO", "T&SL", 19, 3.6, 1.6, 70.4),
				chart("GUIDE", "T&SL", 19, 3.1, 2.0, 120.0),
				chart("PR", "RM", 18, 18.9, 12.2, 467.2),
				chart("MO", "RM", 18, 3.2, 4.6, 78.8),
				chart("GUIDE", "RM", 18, 4.0, 4.9, 200.0)));

		CostMonitoringSnapshotLoader loader = mock(CostMonitoringSnapshotLoader.class);
		when(loader.load(any(Map.class))).thenReturn(CostMonitoringSnapshot.of(response, TODAY));
		when(loader.today()).thenReturn(TODAY);
		return new CostMonitoringLookupAnswers(loader, mock(CostTopItemsRepository.class), mock(CostWorkingDateRepository.class));
	}

	@Test
	void leadsWithForecastAndFlagsStaleOt() {
		AnswerGuide g = answers().guide("Hôm nay có gì bất thường?", Map.of("month", "2026-11"), "vi");

		assertNotNull(g);
		assertEquals(CostMonitoringLookupAnswers.ANOMALY_TODAY, g.intent());
		String text = g.directAnswer();
		System.out.println(text);

		// 1. Câu đầu: không đột biến NHƯNG có khung dự báo vượt
		String first = text.lines().findFirst().orElse("");
		assertTrue(first.startsWith("**Hôm nay không có chi đột biến, nhưng "), first);
		assertTrue(first.contains("khung đang dự báo vượt Fluct**"), first);
		// dữ liệu test: PRESS OT, GUIDE OT dự báo vượt (MOLD OT trong Fluct)
		assertTrue(first.contains("khung đang dự báo vượt Fluct** (gồm 2 khung OT dữ liệu cũ)"), first);
		// "Nặng nhất" không lấy khung dữ liệu cũ (OT chậm 9 ngày)
		assertFalse(first.contains(" OT ("), first);
		assertFalse(text.contains("- **PRESS OT**"), text);
		// Khung OT dự báo vượt nêu ở dòng ⚠
		assertTrue(text.contains("dự báo OT vượt Fluct (PRESS "), text);
		// 3. Tối đa 3 khung nặng nhất + dòng "… và N khung khác"
		assertTrue(text.contains("**Dự báo vượt Fluct nhiều nhất (theo K$):**"));
		// Xếp theo K$ vượt: PRESS T&SL (+100.3) trước MOLD T&SL (+39.6)
		assertTrue(text.indexOf("- **PRESS T&SL**: vượt **+100.3 K$**") >= 0, text);
		assertTrue(text.indexOf("- **PRESS T&SL**") < text.indexOf("- **MOLD T&SL**"), text);
		assertTrue(text.contains("khung khác (xem biểu đồ bên dưới)"));
		// 4. OT chậm 9 ngày -> cảnh báo; không liệt kê OT trong chi gần nhất
		assertTrue(text.contains("⚠ **OT** mới cập nhật đến 10/11"), text);
		assertTrue(text.contains("chậm 9 ngày"));
		assertFalse(text.contains("- OT"));
		// RM trễ 1 ngày: bình thường, không cảnh báo
		assertFalse(text.contains("⚠ **RM**"));
		// 5. Chi gần nhất: 1 dòng / loại chi phí
		assertTrue(text.contains("- T&SL 19/11: PRESS 5.4 (TB 8.5) ↓"), text);
		assertTrue(text.contains("- RM 18/11: "), text);
		assertTrue(text.contains("MOLD 4.6 (TB 3.2) ↑"), text);
	}
}
