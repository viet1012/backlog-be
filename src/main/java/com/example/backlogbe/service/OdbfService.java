package com.example.backlogbe.service;

import com.example.backlogbe.dto.odbf.OdbfSummaryDto;
import com.example.backlogbe.repository.odbf.OdbfRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OdbfService {

	private final OdbfRepository repository;


	// =========================================================
	// SUMMARY
	// =========================================================

	@Transactional(readOnly = true)
	public List<OdbfSummaryDto> getSummary() {

		return repository.findSummary();
	}
}