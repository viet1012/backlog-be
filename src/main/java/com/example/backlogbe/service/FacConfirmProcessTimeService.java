package com.example.backlogbe.service;

import com.example.backlogbe.dto.facconfirm.FacConfirmProcessTimeRequest;
import com.example.backlogbe.repository.facconfirm.FacConfirmProcessTimeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FacConfirmProcessTimeService {

	private static final Map<String, String> PROCESS_MAPPING =
			Map.of(
					"toDrill", "To Drill",
					"toHeat", "To Heat",
					"heatStart", "Heat Start",
					"heatFinish", "Heat Finish",
					"toPk", "To Packing"
			);


	// =========================================================
	// PROCESS MAPPING
	// =========================================================
	private final FacConfirmProcessTimeRepository repository;

	// =========================================================
	// CONFIRMED PROCESSES
	// =========================================================
	@Transactional(readOnly = true)
	public List<Map<String, Object>> getConfirmedProcesses(
			List<String> aufnrs
	) {
		return repository.findConfirmedProcesses(
				aufnrs
		);
	}
	// =========================================================
	// SAVE
	// =========================================================

	@Transactional
	public int save(
			FacConfirmProcessTimeRequest request,
			String machineName
	) {

		validateRequest(request);

		String updater = buildUpdater(
				machineName,
				request.employeeId()
		);

		int updatedCount = 0;

		for (var change : request.changes()) {

			validateChange(
					change.aufnr(),
					change.field(),
					change.value()
			);

			String processGrp =
					PROCESS_MAPPING.get(
							change.field()
					);

			updatedCount += repository.upsert(
					change.aufnr().trim(),
					processGrp,
					change.value(),
					updater
			);
		}

		return updatedCount;
	}


	// =========================================================
	// BUILD UPDATER
	// Example:
	//
	// PC-F2-001_22847
	// =========================================================

	private String buildUpdater(
			String machineName,
			String employeeId
	) {

		String machine =
				machineName == null
						|| machineName.isBlank()
						? "UNKNOWN"
						: machineName.trim();

		return machine
				+ "_"
				+ employeeId.trim();
	}


	// =========================================================
	// VALIDATE REQUEST
	// =========================================================

	private void validateRequest(
			FacConfirmProcessTimeRequest request
	) {

		if (request == null) {
			throw new IllegalArgumentException(
					"Request is required"
			);
		}

		if (
				request.employeeId() == null
						|| request.employeeId().isBlank()
		) {

			throw new IllegalArgumentException(
					"Employee ID is required"
			);
		}

		if (
				request.changes() == null
						|| request.changes().isEmpty()
		) {

			throw new IllegalArgumentException(
					"No process changes to save"
			);
		}

		if (request.changes().size() > 500) {
			throw new IllegalArgumentException(
					"Maximum 500 changes per request"
			);
		}
	}


	// =========================================================
	// VALIDATE CHANGE
	// =========================================================

	private void validateChange(
			String aufnr,
			String field,
			LocalDateTime value
	) {

		if (
				aufnr == null
						|| aufnr.isBlank()
		) {

			throw new IllegalArgumentException(
					"AUFNR is required"
			);
		}

		if (
				field == null
						|| !PROCESS_MAPPING.containsKey(field)
		) {

			throw new IllegalArgumentException(
					"Invalid Fac Confirm field: " + field
			);
		}

		if (value == null) {
			throw new IllegalArgumentException(
					"Confirm time is required"
			);
		}
	}
}