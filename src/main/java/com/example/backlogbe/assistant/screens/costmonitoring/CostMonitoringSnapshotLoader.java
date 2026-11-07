package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.service.CostMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

// =========================================================
// Lấy chart Cost Monitoring qua service hiện có theo tham số màn hình.
//
// screenParams:
//   month    : yyyy-MM hoặc yyyy.MM (bỏ trống = tháng hiện tại)
//   simuWeek : Simu_W1..Simu_W5 (bỏ trống = tuần Simu mới nhất có số)
//   plTgtMode: tên khác của simuWeek
// =========================================================
@Component
@RequiredArgsConstructor
public class CostMonitoringSnapshotLoader {

	private final CostMonitoringService costMonitoringService;

	public CostMonitoringSnapshot load(Map<String, String> screenParams) {

		return load(
				normalizeMonth(screenParams.get("month")),
				simuWeek(screenParams)
		);
	}

	// month: yyyy-MM (null = tháng hiện tại)
	public CostMonitoringSnapshot load(String month, String simuWeek) {

		return CostMonitoringSnapshot.of(
				costMonitoringService.getChart(month, simuWeek),
				today()
		);
	}

	public LocalDate today() {
		return LocalDate.now();
	}

	// Tháng màn hình đang xem (dùng khi tool cần tháng mà không cần chart)
	public YearMonth month(Map<String, String> screenParams) {

		String month = normalizeMonth(screenParams.get("month"));

		if (month == null) {
			return YearMonth.from(today());
		}

		try {
			return YearMonth.parse(month);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("param=month|expected=yyyy-MM");
		}
	}

	public static String simuWeek(Map<String, String> screenParams) {
		return firstNonBlank(screenParams.get("simuWeek"), screenParams.get("plTgtMode"));
	}

	// "2026.10" (dạng của /months) -> "2026-10"; dùng chung cho context, tool và gợi ý
	public static String normalizeMonth(String month) {

		if (month == null || month.isBlank()) {
			return null;
		}

		return month.trim().replace('.', '-');
	}

	private static String firstNonBlank(String a, String b) {

		if (a != null && !a.isBlank()) {
			return a;
		}

		return b != null && !b.isBlank() ? b : null;
	}
}
