package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// RM có actual trễ T&SL 1 ngày: tiến độ / dự báo theo Fluct / ngân sách/ngày phải tính theo ngày actual của khung
// (test 2026-11-10 seed-33: PRESS RM "đã dùng 29.3% Fluct sau 26.7% thời gian → chi chậm hơn tiến độ -3.7 K$")
class CostMonitoringAssessmentLagTest {

	// PRESS RM 2026-11: Fluct 526.7, Actual 154.3 đến ngày 8, không PL; D chung = 9, N = 30
	private static CostChartDto pressRm() {
		return new CostChartDto("PR", "RM", 508.2, null, null, 154.3, null,
				List.of(new CostChartPointDto(LocalDate.of(2026, 11, 8), null, 154.3, null, 526.7)), null);
	}

	@Test
	void usesFrameActualDayWhenItLagsBehindD() {
		CostMonitoringAssessment a = CostMonitoringAssessment.of(pressRm(), 9, 30, false, 8);

		// Theo tiến độ đến ngày 8: 526.7 × 8 / 30 = 140.5 -> chi nhanh hơn +13.8
		assertEquals(13.8, a.excessVsPace(), 1e-9);
		// Dự báo theo Fluct: 154.3 + 526.7 / 30 × 22 = 540.5 -> vượt Fluct +13.8
		assertEquals(540.5, CmText.r1(a.estimateEom()), 1e-9);
		assertEquals(CostMonitoringAssessment.WILL_EXCEED, a.forecastStatus());
		// Ngân sách/ngày: (526.7 − 154.3) / 22
		assertEquals(372.4 / 22, a.remainingPerDay(), 1e-9);
	}

	@Test
	void unchangedWhenActualDayEqualsD() {
		CostMonitoringAssessment a = CostMonitoringAssessment.of(pressRm(), 8, 30, false, 8);
		CostMonitoringAssessment lagged = CostMonitoringAssessment.of(pressRm(), 9, 30, false, 8);

		assertEquals(a.excessVsPace(), lagged.excessVsPace());
		assertEquals(a.estimateEom(), lagged.estimateEom());
	}
}
