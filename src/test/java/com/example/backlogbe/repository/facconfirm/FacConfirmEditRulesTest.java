package com.example.backlogbe.repository.facconfirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FacConfirmEditRulesTest {

	private static final FacConfirmEditRules.EditRow NORMAL =
			new FacConfirmEditRules.EditRow("A", false);

	private static final FacConfirmEditRules.EditRow NO_HEAT =
			new FacConfirmEditRules.EditRow("B", true);


	@Test
	void heatOnlyConfirmsHeatFinish() {

		assertEquals(
				List.of(FacConfirmEditRules.HEAT_FINISH),
				FacConfirmEditRules.getEditableFields(NORMAL, FacConfirmEditRules.HEAT)
		);

		assertEquals(
				"Heat Finish",
				FacConfirmEditRules.getFinalProcess(NORMAL, FacConfirmEditRules.HEAT)
		);
	}


	@Test
	void heatStartIsReadOnlyEverywhere() {

		assertTrue(FacConfirmEditRules.isReadOnlyField(FacConfirmEditRules.HEAT_START));

		for (FacConfirmEditRules.EditRow row : List.of(NORMAL, NO_HEAT)) {
			assertFalse(
					FacConfirmEditRules.getEditableFieldsAnyProcess(row)
							.contains(FacConfirmEditRules.HEAT_START)
			);
		}

		// Bản ghi 'Heat Start' cũ vẫn hiển thị thuộc Heat.
		assertEquals(
				FacConfirmEditRules.HEAT,
				FacConfirmEditRules.getOwnerProcess(NORMAL, FacConfirmEditRules.HEAT_START)
		);
	}


	@Test
	void roughNoHeatRuleUnchanged() {

		assertEquals(
				List.of(FacConfirmEditRules.TO_DRILL, FacConfirmEditRules.HEAT_FINISH),
				FacConfirmEditRules.getEditableFields(NO_HEAT, FacConfirmEditRules.ROUGH)
		);

		assertEquals(
				"Heat Finish",
				FacConfirmEditRules.getFinalProcess(NO_HEAT, FacConfirmEditRules.ROUGH)
		);

		assertEquals(
				"To Heat",
				FacConfirmEditRules.getFinalProcess(NORMAL, FacConfirmEditRules.ROUGH)
		);
	}


	// =====================================================
	// validateTimes
	// =====================================================

	private static final LocalDateTime NOW =
			LocalDateTime.of(2026, 10, 8, 12, 0);


	@Test
	void futureTimeIsAllowed() {

		// Không giới hạn thời gian ở tương lai (theo yêu cầu nghiệp vụ)
		assertTrue(
				FacConfirmEditRules.validateTimes(
						NORMAL, Map.of(),
						Map.of(FacConfirmEditRules.TO_DRILL, NOW.plusYears(1))
				).isEmpty()
		);
	}


	@Test
	void noHeatToClgCannotBeBeforeToDrill() {

		List<String> errors =
				FacConfirmEditRules.validateTimes(
						NO_HEAT,
						Map.of(FacConfirmEditRules.TO_DRILL, NOW.minusHours(1)),
						Map.of(FacConfirmEditRules.HEAT_FINISH, NOW.minusHours(2))
				);

		assertEquals(
				List.of("PO B: To CLG (08/10/2026 10:00) không được trước To Drill (08/10/2026 11:00)"),
				errors
		);
	}


	@Test
	void normalRowChecksFullOrderButNotUnchangedPairs() {

		// To Heat cũ sai thứ tự với To Drill cũ, nhưng không bị báo vì không sửa
		Map<String, LocalDateTime> current = Map.of(
				FacConfirmEditRules.TO_DRILL, NOW.minusHours(1),
				FacConfirmEditRules.TO_HEAT, NOW.minusHours(3)
		);

		assertTrue(
				FacConfirmEditRules.validateTimes(
						NORMAL, current,
						Map.of(FacConfirmEditRules.TO_PK, NOW)
				).isEmpty()
		);

		List<String> errors =
				FacConfirmEditRules.validateTimes(
						NORMAL, current,
						Map.of(FacConfirmEditRules.HEAT_FINISH, NOW.minusHours(2))
				);

		// To CLG trước To Drill (To Heat ở trước To CLG nên hợp lệ)
		assertEquals(1, errors.size());
		assertTrue(errors.get(0).contains("To CLG"));
		assertTrue(errors.get(0).contains("To Drill"));
	}
}
