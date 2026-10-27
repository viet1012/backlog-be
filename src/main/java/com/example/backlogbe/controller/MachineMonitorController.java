package com.example.backlogbe.controller;

import com.example.backlogbe.dto.machinemonitor.MachineMonitorHseDto;
import com.example.backlogbe.service.MachineMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Chỉ đọc, không cần đăng nhập.
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/machine-monitor")
@RequiredArgsConstructor
public class MachineMonitorController {

	private final MachineMonitorService service;


	// =========================================================
	// HSE (tab "An toàn")
	// =========================================================

	@GetMapping("/hse")
	public ResponseEntity<MachineMonitorHseDto> getHse(
			// Không đánh dấu required để service trả lỗi thống nhất
			@RequestParam(required = false) String floorId,
			// yyyy-MM-dd; chỉ lọc lỗi SAFE theo ngày của hse_date (cả 2 đầu tính luôn)
			@RequestParam(required = false) String safeFrom,
			@RequestParam(required = false) String safeTo
	) {

		return ResponseEntity.ok(
				service.getHse(floorId, safeFrom, safeTo)
		);
	}
}
