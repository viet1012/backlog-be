package com.example.backlogbe.controller;

import com.example.backlogbe.auth.Perm;
import com.example.backlogbe.auth.RequirePerm;
import com.example.backlogbe.dto.odbf.OdbfSummaryDto;
import com.example.backlogbe.service.OdbfService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Quyền: ODBF – GET = V (BE chưa có API export)
@RestController
@RequestMapping("/api/odbf")
@RequiredArgsConstructor
@RequirePerm(feature = Perm.ODBF, action = Perm.VIEW)
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
