package com.example.backlogbe.repository.facconfirm;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

// =========================================================
// Trạng thái hiện tại của một PO, dùng khi kiểm tra request lưu.
//
// backlogValues: giá trị trong F2_Backlog_Main, theo field
// confirmValues: bản ghi F2_Backlog_Fac_Confirm mới nhất, theo field
// =========================================================
public record FacConfirmEditState(
		String aufnr,
		boolean noHeat,
		Map<String, LocalDateTime> backlogValues,
		Map<String, LocalDateTime> confirmValues
) {

	// PO không có trong F2_Backlog_Main
	public static FacConfirmEditState empty(String aufnr) {
		return new FacConfirmEditState(
				aufnr,
				false,
				Map.of(),
				Map.of()
		);
	}

	public FacConfirmEditRules.EditRow toEditRow() {
		return new FacConfirmEditRules.EditRow(
				aufnr,
				noHeat
		);
	}

	// Giá trị đang hiển thị: Backlog ưu tiên, sau đó Fac Confirm (giống FacData)
	public Map<String, LocalDateTime> currentValues() {

		Map<String, LocalDateTime> current =
				new LinkedHashMap<>(confirmValues);

		current.putAll(backlogValues);

		return current;
	}
}
