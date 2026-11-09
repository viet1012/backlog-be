package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartForecastDto;
import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;

// =========================================================
// GẮN DỰ BÁO DO BE TÍNH VÀO RESPONSE GET /api/cost-monitoring/chart
//
// Mỗi khung: CostMonitoringAssessment.of(snapshot, chart) – cùng cách tính với trợ lý, /detail, /suggestions.
// Không sửa snapshot trong cache (tạo response mới).
// =========================================================
public final class CostChartForecasts {

	private CostChartForecasts() {
	}

	public static CostMonitoringChartResponse withForecasts(CostMonitoringSnapshot snapshot) {

		CostMonitoringChartResponse response = snapshot.response();

		return new CostMonitoringChartResponse(
				response.month(),
				response.simuWeek(),
				response.unit(),
				response.charts().stream()
						.map(chart -> chart.withForecast(of(CostMonitoringAssessment.of(snapshot, chart))))
						.toList()
		);
	}

	static CostChartForecastDto of(CostMonitoringAssessment a) {

		return new CostChartForecastDto(
				status(a),
				a.pct(),
				a.estimateBase(),
				a.estimateEom(),
				a.estimateOver(),
				a.estimateOverFc(),
				a.estimatePctOfFluct(),
				a.estimateOverPct(),
				a.projectedEom(),
				a.projectedOver(),
				a.projectedOverFc(),
				a.projectedPctOfFluct(),
				a.projectedOverPct()
		);
	}

	// Trạng thái chính: ủy quyền cho CostMonitoringAssessment.mainStatus (hàm duy nhất, giá trị trùng hằng của DTO)
	static String status(CostMonitoringAssessment a) {
		return a.mainStatus();
	}
}
