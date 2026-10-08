package com.example.backlogbe.service;

import com.example.backlogbe.dto.facconfirm.FacConfirmProcessTimeRequest;
import com.example.backlogbe.repository.facconfirm.FacConfirmEditRules;
import com.example.backlogbe.repository.facconfirm.FacConfirmEditState;
import com.example.backlogbe.repository.facconfirm.FacConfirmProcessTimeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

		validateReadOnlyFields(
				request.changes()
		);

		Map<String, FacConfirmEditState> states =
				repository.findEditStates(
						request.changes().stream()
								.map(change -> change.aufnr().trim())
								.distinct()
								.toList()
				);

		validateEditableFields(
				request.changes(),
				procGrp,
				states
		);

		validateTimes(
				request.changes(),
				states
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
	// Heat Start chỉ để xem: từ chối ở mọi công đoạn.
	// Dòng không có Heat: Rough sửa To Drill + To CLG, không được sửa To Heat.
	// =========================================================

	private void validateReadOnlyFields(
			List<FacConfirmProcessTimeRequest.ProcessTimeItem> changes
	) {

		List<String> readOnlyAufnrs =
				changes.stream()
						.filter(change -> FacConfirmEditRules.isReadOnlyField(change.field()))
						.map(change -> change.aufnr().trim())
						.distinct()
						.toList();

		if (!readOnlyAufnrs.isEmpty()) {
			throw new IllegalArgumentException(
					"Heat Start chỉ để xem, không xác nhận được: "
							+ String.join(", ", readOnlyAufnrs)
			);
		}

	}

	private void validateEditableFields(
			List<FacConfirmProcessTimeRequest.ProcessTimeItem> changes,
			String procGrp,
			Map<String, FacConfirmEditState> states
	) {

		List<String> violations = new ArrayList<>();

		for (var change : changes) {

			String aufnr =
					change.aufnr().trim();

			FacConfirmEditRules.EditRow row =
					stateOf(states, aufnr).toEditRow();

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
	// VALIDATE TIMES (FacConfirmEditRules.validateTimes)
	//
	// - Không ở tương lai (lệch tối đa MAX_CLOCK_SKEW).
	// - Đúng thứ tự công đoạn theo loại dòng, so với giá trị đang hiển thị.
	// =========================================================

	private void validateTimes(
			List<FacConfirmProcessTimeRequest.ProcessTimeItem> changes,
			Map<String, FacConfirmEditState> states
	) {

		// AUFNR -> (field -> giá trị mới); trùng field thì lấy giá trị sau cùng
		Map<String, Map<String, LocalDateTime>> changesByAufnr =
				new LinkedHashMap<>();

		for (var change : changes) {
			changesByAufnr
					.computeIfAbsent(change.aufnr().trim(), key -> new LinkedHashMap<>())
					.put(change.field(), change.value());
		}

		LocalDateTime now = LocalDateTime.now();

		List<String> errors = new ArrayList<>();

		changesByAufnr.forEach((aufnr, aufnrChanges) -> {

			FacConfirmEditState state =
					stateOf(states, aufnr);

			errors.addAll(
					FacConfirmEditRules.validateTimes(
							state.toEditRow(),
							state.currentValues(),
							aufnrChanges,
							now
					)
			);
		});

		if (!errors.isEmpty()) {
			throw new IllegalArgumentException(
					"Thời gian không hợp lệ: "
							+ String.join("; ", errors)
			);
		}
	}

	private FacConfirmEditState stateOf(
			Map<String, FacConfirmEditState> states,
			String aufnr
	) {
		return states.getOrDefault(
				aufnr,
				FacConfirmEditState.empty(aufnr)
		);
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