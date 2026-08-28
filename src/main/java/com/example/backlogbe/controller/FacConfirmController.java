package com.example.backlogbe.controller;

import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.dto.facconfirm.FacConfirmDto;
import com.example.backlogbe.dto.facconfirm.FacConfirmFilterOptionsRequest;
import com.example.backlogbe.dto.facconfirm.FacConfirmProcessGroupDto;
import com.example.backlogbe.dto.facconfirm.FacConfirmSearchRequest;
import com.example.backlogbe.service.FacConfirmService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;


@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/fac-confirm")
@RequiredArgsConstructor
public class FacConfirmController {

	private final FacConfirmService service;


	@GetMapping
	public ResponseEntity<PageResponse<FacConfirmDto>> getFacConfirm(
			@RequestParam String div,

			@RequestParam
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
			LocalDate expD,

			@RequestParam String procGrp,

			@RequestParam(defaultValue = "0")
			int page,

			@RequestParam(defaultValue = "20")
			int size
	) {

		return ResponseEntity.ok(
				service.getFacConfirm(
						div,
						expD,
						procGrp,
						page,
						size
				)
		);
	}


	@GetMapping("/process-groups")
	public ResponseEntity<List<FacConfirmProcessGroupDto>> getProcessGroups(
			@RequestParam String div,

			@RequestParam
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
			LocalDate expD
	) {

		return ResponseEntity.ok(
				service.getProcessGroups(
						div,
						expD
				)
		);
	}


	@PostMapping("/search")
	public ResponseEntity<PageResponse<FacConfirmDto>> search(
			@RequestBody
			FacConfirmSearchRequest request
	) {

		return ResponseEntity.ok(
				service.search(
						request
				)
		);
	}


	@PostMapping("/filter-options")
	public ResponseEntity<List<String>> getFilterOptions(
			@RequestBody
			FacConfirmFilterOptionsRequest request
	) {

		return ResponseEntity.ok(
				service.getFilterOptions(
						request
				)
		);
	}
}