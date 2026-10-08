package com.example.backlogbe.service;

import com.example.backlogbe.dto.facconfirm.FacConfirmProcessTimeRequest;
import com.example.backlogbe.repository.facconfirm.FacConfirmEditRules;
import com.example.backlogbe.repository.facconfirm.FacConfirmProcessTimeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FacConfirmProcessTimeService {

	// field -> ProcessGrp trong F2_Backlog_Fac_Confirm
	private static final Map<String, String> PROCESS_MAPPING =
			FacConfirmEditRules.FIELD_TO_DB_PROCESS;


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

		String procGrp =
				normalizeProcessGroup(
						request.procGrp()
				);

		String updater = buildUpdater(
				machineName,
				request.employeeId()
		);

		for (var change : request.changes()) {

			validateChange(
					change.aufnr(),
					change.field(),
					change.value()
			);
		}

		validateEditableFields(
				request.changes(),
				procGrp
		);

		int updatedCount = 0;

		for (var change : request.changes()) {

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
	// VALIDATE EDITABLE FIELDS
	//
	// Theo FacConfirmEditRules.getEditableFields:
	// - Có procGrp: field phải được sửa ở đúng công đoạn đó.
	// - Không có procGrp: field phải được sửa ở ít nhất một công đoạn.
	//
	// Dòng không có Heat: Rough sửa To Drill + To CLG,
	// không được sửa To Heat / Heat Start.
	// =========================================================

	private void validateEditableFields(
			List<FacConfirmProcessTimeRequest.ProcessTimeItem> changes,
			String procGrp
	) {

		Set<String> noHeatAufnrs =
				new HashSet<>(
						repository.findNoHeatNoteAufnrs(
								changes.stream()
										.map(change -> change.aufnr().trim())
										.distinct()
										.toList()
						)
				);

		List<String> violations = new ArrayList<>();

		for (var change : changes) {

			String aufnr =
					change.aufnr().trim();

			FacConfirmEditRules.EditRow row =
					new FacConfirmEditRules.EditRow(
							aufnr,
							noHeatAufnrs.contains(aufnr)
					);

			Collection<String> editableFields =
					procGrp != null
							? FacConfirmEditRules.getEditableFields(row, procGrp)
							: FacConfirmEditRules.getEditableFieldsAnyProcess(row);

			if (!editableFields.contains(change.field())) {

				String violation =
						aufnr
								+ " ("
								+ FacConfirmEditRules.labelOf(change.field())
								+ (row.noHeat()
								? ", Heat Note \"" + FacConfirmEditRules.NO_HEAT_NOTE_LABEL + "\""
								: "")
								+ ")";

				if (!violations.contains(violation)) {
					violations.add(violation);
				}
			}
		}

		if (!violations.isEmpty()) {
			throw new IllegalArgumentException(
					"Field not editable"
							+ (procGrp != null ? " in " + procGrp : "")
							+ ": "
							+ String.join(", ", violations)
			);
		}
	}


	// =========================================================
	// NORMALIZE PROCESS GROUP (optional)
	// =========================================================

	private String normalizeProcessGroup(
			String value
	) {

		if (
				value == null
						|| value.isBlank()
		) {
			return null;
		}

		return FacConfirmEditRules.PROCESS_GROUPS
				.stream()
				.filter(item -> item.equalsIgnoreCase(value.trim()))
				.findFirst()
				.orElseThrow(() ->
						new IllegalArgumentException(
								"Invalid procGrp: "
										+ value
										+ ". Allowed values: Rough, Heat, Fine"
						)
				);
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