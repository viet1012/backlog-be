package com.example.backlogbe.repository.facconfirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
}
