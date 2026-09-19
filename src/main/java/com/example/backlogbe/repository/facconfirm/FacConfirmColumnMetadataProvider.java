
package com.example.backlogbe.repository.facconfirm;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class FacConfirmColumnMetadataProvider {

	private final Map<String, ColumnMeta> columns =
			new HashMap<>();


	public FacConfirmColumnMetadataProvider() {

		// =====================================================
		// TEXT
		// =====================================================

		register(
				"ferth",
				"FERTH",
				ColumnType.TEXT
		);

		register(
				"productGrp",
				"ProductGrp",
				ColumnType.TEXT
		);

		register(
				"aufnr",
				"AUFNR",
				ColumnType.TEXT
		);

		register(
				"zglobalCode",
				"ZGLOBAL_CODE",
				ColumnType.TEXT
		);

		register(
				"cusId",
				"CusId",
				ColumnType.TEXT
		);

		register(
				"shipBy",
				"ShipBy",
				ColumnType.TEXT
		);

		register(
				"mtoId",
				"MTO_ID",
				ColumnType.TEXT
		);

		register(
				"prtAddcmt2",
				"PRT_ADDCMT2",
				ColumnType.TEXT
		);

		register(
				"currentProcess",
				"CurrentProcess",
				ColumnType.TEXT
		);

		register(
				"pname",
				"PNAME",
				ColumnType.TEXT
		);

		register(
				"note",
				"Note",
				ColumnType.TEXT
		);
		// =====================================================
		// NUMBER
		// =====================================================

		register(
				"finalQty",
				"FinalQty",
				ColumnType.NUMBER
		);


		// =====================================================
		// DATE
		// =====================================================

		register(
				"issueD",
				"IssueD",
				ColumnType.DATE
		);

		register(
				"exportD",
				"ExportD",
				ColumnType.DATE
		);


		// =====================================================
		// EFFECTIVE PROCESS DATE
		//
		// Các field dưới đây KHÔNG còn filter trực tiếp vào
		// column vật lý của F2_Backlog_Main.
		//
		// Chúng filter trên FacData.
		// =====================================================

		register(
				"toDrill",
				"ToDrill",
				ColumnType.DATE
		);

		register(
				"toHeat",
				"ToHeat",
				ColumnType.DATE
		);

		register(
				"heatStart",
				"Heat_Start",
				ColumnType.DATE
		);

		register(
				"heatFinish",
				"Heat_Finish",
				ColumnType.DATE
		);

		register(
				"toPk",
				"ToPK",
				ColumnType.DATE
		);


		// =====================================================
		// SUPPORT BACKEND / ALIAS NAMES
		//
		// Nếu request cũ còn gửi PascalCase / SQL alias
		// thì vẫn hoạt động.
		// =====================================================

		register(
				"FERTH",
				"FERTH",
				ColumnType.TEXT
		);

		register(
				"ProductGrp",
				"ProductGrp",
				ColumnType.TEXT
		);

		register(
				"AUFNR",
				"AUFNR",
				ColumnType.TEXT
		);

		register(
				"ZGLOBAL_CODE",
				"ZGLOBAL_CODE",
				ColumnType.TEXT
		);

		register(
				"PNAME",
				"PNAME",
				ColumnType.TEXT
		);

		register(
				"IssueD",
				"IssueD",
				ColumnType.DATE
		);

		register(
				"ExportD",
				"ExportD",
				ColumnType.DATE
		);

		register(
				"CusId",
				"CusId",
				ColumnType.TEXT
		);

		register(
				"ShipBy",
				"ShipBy",
				ColumnType.TEXT
		);

		register(
				"MTO_ID",
				"MTO_ID",
				ColumnType.TEXT
		);

		register(
				"PRT_ADDCMT2",
				"PRT_ADDCMT2",
				ColumnType.TEXT
		);

		register(
				"CurrentProcess",
				"CurrentProcess",
				ColumnType.TEXT
		);

		register(
				"FinalQty",
				"FinalQty",
				ColumnType.NUMBER
		);

		register(
				"ToDrill",
				"ToDrill",
				ColumnType.DATE
		);

		register(
				"ToHeat",
				"ToHeat",
				ColumnType.DATE
		);

		register(
				"Heat_Start",
				"Heat_Start",
				ColumnType.DATE
		);

		register(
				"Heat_Finish",
				"Heat_Finish",
				ColumnType.DATE
		);

		register(
				"ToPK",
				"ToPK",
				ColumnType.DATE
		);
	}


	// =========================================================
	// REGISTER
	// =========================================================

	private void register(
			String field,
			String sqlColumn,
			ColumnType type
	) {

		columns.put(
				normalize(field),
				new ColumnMeta(
						sqlColumn,
						type
				)
		);
	}


	// =========================================================
	// GET
	// =========================================================

	public ColumnMeta get(
			String field
	) {

		if (
				field == null
						|| field.isBlank()
		) {

			throw new IllegalArgumentException(
					"Fac Confirm filter field is required"
			);
		}


		ColumnMeta meta =
				columns.get(
						normalize(field)
				);


		if (meta == null) {

			throw new IllegalArgumentException(
					"Unsupported Fac Confirm filter field: "
							+ field
			);
		}


		return meta;
	}


	// =========================================================
	// NORMALIZE
	// =========================================================

	private String normalize(
			String value
	) {

		return value
				.trim()
				.toLowerCase(
						Locale.ROOT
				);
	}


	// =========================================================
	// TYPE
	// =========================================================

	public enum ColumnType {

		TEXT,

		NUMBER,

		DATE
	}


	// =========================================================
	// META
	// =========================================================

	public record ColumnMeta(
			String name,
			ColumnType type
	) {
	}
}