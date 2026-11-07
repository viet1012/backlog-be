package com.example.backlogbe.controller;

import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;
import com.example.backlogbe.dto.costmonitoring.CostPlWeeksResponse;
import com.example.backlogbe.service.CostMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Chỉ đọc, không cần đăng nhập.
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/cost-monitoring")
@RequiredArgsConstructor
public class CostMonitoringController {

	private final CostMonitoringService service;


	// =========================================================
	// CHART
	// =========================================================

	@GetMapping("/chart")
	public ResponseEntity<CostMonitoringChartResponse> getChart(
			// yyyy-MM; bỏ trống = tháng hiện tại
			@RequestParam(required = false) String month,
			// null hoặc Simu_W1..Simu_W5
			@RequestParam(required = false) String simuWeek
	) {

		return ResponseEntity.ok(
				service.getChart(month, simuWeek)
		);
	}


	// =========================================================
	// PL SIMULATION WEEK
	// =========================================================

	// Tuần Simulation có số trong F2_PL_TGT của tháng + tuần "Mới nhất" đang dùng
	@GetMapping("/pl-weeks")
	public ResponseEntity<CostPlWeeksResponse> getPlWeeks(
			// yyyy-MM; bỏ trống = tháng hiện tại
			@RequestParam(required = false) String month
	) {

		return ResponseEntity.ok(
				service.getPlWeeks(month)
		);
	}


	// =========================================================
	// MONTH
	// =========================================================

	// Các tháng có target trong F2_PL_TGT, dạng "2026.10", mới nhất trước
	@GetMapping("/months")
	public ResponseEntity<List<String>> getMonths() {

		return ResponseEntity.ok(
				service.getAvailableMonths()
		);
	}
}
