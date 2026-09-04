package com.example.backlogbe.controller;

import com.example.backlogbe.dto.odbf.OdbfSummaryDto;
import com.example.backlogbe.service.OdbfService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/odbf")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")

public class OdbfController {

	private final OdbfService service;


	// =========================================================
	// SUMMARY
	// =========================================================

	@GetMapping("/summary")
	public List<OdbfSummaryDto> getSummary() {

		return service.getSummary();
	}
}