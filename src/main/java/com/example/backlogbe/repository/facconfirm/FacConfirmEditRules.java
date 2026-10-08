package com.example.backlogbe.repository.facconfirm;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// =========================================================
// FAC CONFIRM EDIT RULES
//
// Nguồn duy nhất cho:
// - Điều kiện "Không có Heat" (SQL + Java)
// - Field nào được sửa ở công đoạn nào (getEditableFields)
// - Ô thuộc công đoạn nào (getOwnerProcess)
//
// Quy tắc đặc biệt "rough-no-heat":
// dòng không có Heat thì Rough nhập To Drill + To CLG (heatFinish),
// dòng đó không thuộc công đoạn Heat.
// =========================================================
public final class FacConfirmEditRules {

	// =====================================================
	// PROCESS GROUP
	// =====================================================

	public static final String ROUGH = "Rough";
	public static final String HEAT = "Heat";
	public static final String FINE = "Fine";

	public static final List<String> PROCESS_GROUPS =
			List.of(ROUGH, HEAT, FINE);


	// =====================================================
	// FIELD
	// =====================================================

	public static final String TO_DRILL = "toDrill";
	public static final String TO_HEAT = "toHeat";
	public static final String HEAT_START = "heatStart";
	public static final String HEAT_FINISH = "heatFinish";
	public static final String TO_PK = "toPk";

	// field -> ProcessGrp lưu trong F2_Backlog_Fac_Confirm
	public static final Map<String, String> FIELD_TO_DB_PROCESS =
			orderedMap(
					TO_DRILL, "To Drill",
					TO_HEAT, "To Heat",
					HEAT_START, "Heat Start",
					HEAT_FINISH, "Heat Finish",
					TO_PK, "To Packing"
			);

	// field -> tiêu đề hiển thị
	public static final Map<String, String> FIELD_LABELS =
			orderedMap(
					TO_DRILL, "To Drill",
					TO_HEAT, "To Heat",
					HEAT_START, "Heat Start",
					HEAT_FINISH, "To CLG",
					TO_PK, "To Packing"
			);

	private static final Map<String, List<String>> DEFAULT_EDITABLE_FIELDS =
			Map.of(
					ROUGH, List.of(TO_DRILL, TO_HEAT),
					HEAT, List.of(HEAT_START, HEAT_FINISH),
					FINE, List.of(TO_PK)
			);

	private static final Map<String, String> DEFAULT_OWNER_PROCESS =
			Map.of(
					TO_DRILL, ROUGH,
					TO_HEAT, ROUGH,
					HEAT_START, HEAT,
					HEAT_FINISH, HEAT,
					TO_PK, FINE
			);

	private static final List<String> ROUGH_NO_HEAT_FIELDS =
			List.of(TO_DRILL, HEAT_FINISH);


	// =====================================================
	// "KHÔNG CÓ HEAT" - ĐIỀU KIỆN GỐC
	//
	// F2_Backlog_Main (alias bl):
	// - Heat_Note = 'NO HEAT' (trim + upper)
	// - WaitingDays không phải 2 / 5 / 7
	//   (CASE Heat Note ưu tiên TD / DC53 / Molypden trước)
	//
	// FacData tính ra IsNoHeatNote từ điều kiện này;
	// HasHeatProcess và Heat Note "Không có Heat" đều dựa trên nó.
	// =====================================================

	public static final String NO_HEAT_NOTE_LABEL = "Không có Heat";

	static final String NO_HEAT_CONDITION = """
			(
			    UPPER(
			        LTRIM(
			            RTRIM(
			                ISNULL(bl.Heat_Note, '')
			            )
			        )
			    ) = 'NO HEAT'
			    AND ISNULL(bl.WaitingDays, -1) NOT IN (2, 5, 7)
			)
			""";

	// Bọc ngoặc vì text block cắt khoảng trắng cuối dòng ("AND """ + ...).
	static String isNoHeatSql(String alias) {
		return "(" + alias + ".IsNoHeatNote = 1)";
	}

	static String hasHeatSql(String alias) {
		return "(" + alias + ".IsNoHeatNote = 0)";
	}


	// =====================================================
	// ROW
	// =====================================================

	public record EditRow(
			String aufnr,
			boolean noHeat
	) {
	}


	private FacConfirmEditRules() {
	}


	// =====================================================
	// EDITABLE FIELDS
	// =====================================================

	public static List<String> getEditableFields(
			EditRow row,
			String procGrp
	) {

		if (row.noHeat()) {

			if (ROUGH.equals(procGrp)) {
				return ROUGH_NO_HEAT_FIELDS;
			}

			// Dòng không có Heat không thuộc công đoạn Heat.
			if (HEAT.equals(procGrp)) {
				return List.of();
			}
		}

		return DEFAULT_EDITABLE_FIELDS.getOrDefault(
				procGrp,
				List.of()
		);
	}

	// Field được sửa ở ít nhất một công đoạn (khi request không gửi procGrp).
	public static Set<String> getEditableFieldsAnyProcess(
			EditRow row
	) {

		Set<String> fields = new LinkedHashSet<>();

		for (String procGrp : PROCESS_GROUPS) {
			fields.addAll(
					getEditableFields(row, procGrp)
			);
		}

		return fields;
	}


	// =====================================================
	// FINAL PROCESS
	//
	// Process cuối của công đoạn theo từng dòng
	// (= field cuối trong getEditableFields), dạng ProcessGrp DB.
	// Công đoạn được tính "Đã xác nhận" khi có bản ghi process này
	// trong F2_Backlog_Fac_Confirm.
	//
	// Rough: dòng thường 'To Heat', dòng không có Heat 'Heat Finish' (To CLG)
	// Heat : 'Heat Finish' (dòng không có Heat: null - không thuộc Heat)
	// Fine : 'To Packing'
	// =====================================================

	public static String getFinalProcess(
			EditRow row,
			String procGrp
	) {

		List<String> fields =
				getEditableFields(row, procGrp);

		if (fields.isEmpty()) {
			return null;
		}

		return FIELD_TO_DB_PROCESS.get(
				fields.get(fields.size() - 1)
		);
	}

	// SQL của getFinalProcess cho dòng FacData (alias), dùng trong query thẻ tổng hợp.
	static String finalProcessSql(
			String alias,
			String procGrp
	) {

		String noHeatProcess =
				getFinalProcess(new EditRow(null, true), procGrp);

		String normalProcess =
				getFinalProcess(new EditRow(null, false), procGrp);

		if (Objects.equals(noHeatProcess, normalProcess)) {
			return sqlLiteral(normalProcess);
		}

		return "CASE WHEN "
				+ isNoHeatSql(alias)
				+ " THEN "
				+ sqlLiteral(noHeatProcess)
				+ " ELSE "
				+ sqlLiteral(normalProcess)
				+ " END";
	}

	// Danh sách mọi process cuối, dạng SQL: 'A', 'B', ...
	static String finalProcessesSqlList() {

		Set<String> processes = new LinkedHashSet<>();

		for (String procGrp : PROCESS_GROUPS) {
			for (boolean noHeat : new boolean[] {false, true}) {

				String process =
						getFinalProcess(new EditRow(null, noHeat), procGrp);

				if (process != null) {
					processes.add(process);
				}
			}
		}

		return String.join(
				", ",
				processes.stream()
						.map(FacConfirmEditRules::sqlLiteral)
						.toList()
		);
	}

	private static String sqlLiteral(String value) {

		if (value == null) {
			return "NULL";
		}

		return "'" + value.replace("'", "''") + "'";
	}


	// =====================================================
	// OWNER PROCESS
	// =====================================================

	public static String getOwnerProcess(
			EditRow row,
			String field
	) {

		if (
				row.noHeat()
						&& HEAT_FINISH.equals(field)
		) {
			return ROUGH;
		}

		return DEFAULT_OWNER_PROCESS.get(field);
	}

	public static String fieldOfDbProcess(
			String dbProcess
	) {

		for (Map.Entry<String, String> entry : FIELD_TO_DB_PROCESS.entrySet()) {
			if (entry.getValue().equals(dbProcess)) {
				return entry.getKey();
			}
		}

		return null;
	}

	public static String labelOf(String field) {
		return FIELD_LABELS.getOrDefault(field, field);
	}


	private static Map<String, String> orderedMap(String... keyValues) {

		Map<String, String> map = new LinkedHashMap<>();

		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}

		return java.util.Collections.unmodifiableMap(map);
	}
}