//package com.example.backlogbe.repository.facconfirm;
//
//import jakarta.annotation.PostConstruct;
//import lombok.RequiredArgsConstructor;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.jdbc.core.ResultSetExtractor;
//import org.springframework.stereotype.Component;
//
//import java.sql.ResultSetMetaData;
//import java.util.HashMap;
//import java.util.Locale;
//import java.util.Map;
//
//
//@Component
//@RequiredArgsConstructor
//public class FacConfirmColumnMetadataProvider {
//
//	private static final Map<String, String> FIELD_MAPPING =
//			Map.ofEntries(
//
//					Map.entry(
//							"ferth",
//							"FERTH"
//					),
//
//					Map.entry(
//							"productGrp",
//							"ProductGrp"
//					),
//
//					Map.entry(
//							"aufnr",
//							"AUFNR"
//					),
//
//					Map.entry(
//							"zglobalCode",
//							"ZGLOBAL_CODE"
//					),
//
//					Map.entry(
//							"pname",
//							"PNAME"
//					),
//
//					Map.entry(
//							"issueD",
//							"IssueD"
//					),
//
//					Map.entry(
//							"exportD",
//							"ExportD"
//					),
//
//					Map.entry(
//							"cusId",
//							"RRONYU1"
//					),
//
//					Map.entry(
//							"shipBy",
//							"ShipBy"
//					),
//
//					Map.entry(
//							"mtoId",
//							"MTO_ID"
//					),
//
//					Map.entry(
//							"prtAddcmt2",
//							"PRT_ADDCMT2"
//					),
//
//					Map.entry(
//							"currentProcess",
//							"CurrentProcess"
//					),
//
//					Map.entry(
//							"finalQty",
//							"FinalQty"
//					),
//
//					Map.entry(
//							"toDrill",
//							"ToDrill"
//					),
//
//					Map.entry(
//							"toHeat",
//							"ToHeat"
//					),
//
//					Map.entry(
//							"heatStart",
//							"TimeSQuenching"
//					),
//
//					Map.entry(
//							"heatFinish",
//							"TimeFHeat"
//					),
//
//					Map.entry(
//							"toPk",
//							"ToPK"
//					)
//			);
//	private final JdbcTemplate jdbcTemplate;
//
//
//	// =========================================================
//	// FAC CONFIRM FIELD -> DB FIELD
//	// =========================================================
//	private final Map<String, ColumnMeta> columns =
//			new HashMap<>();
//
//
//	// =========================================================
//	// LOAD METADATA
//	// =========================================================
//
//	@PostConstruct
//	public void load() {
//
//		columns.clear();
//
//
//		jdbcTemplate.query(
//				"""
//						SELECT TOP 0 *
//						FROM F2_Backlog_Main
//						""",
//
//				(ResultSetExtractor<Void>) rs -> {
//
//					ResultSetMetaData meta =
//							rs.getMetaData();
//
//
//					for (
//							int i = 1;
//							i <= meta.getColumnCount();
//							i++
//					) {
//
//						String name =
//								meta.getColumnName(i);
//
//
//						String sqlType =
//								meta.getColumnTypeName(i);
//
//
//						columns.put(
//								normalize(name),
//
//								new ColumnMeta(
//										name,
//										resolveType(
//												sqlType
//										)
//								)
//						);
//					}
//
//
//					return null;
//				}
//		);
//
//
//		if (columns.isEmpty()) {
//
//			throw new IllegalStateException(
//					"Unable to load F2_Backlog_Main metadata"
//			);
//		}
//	}
//
//
//	// =========================================================
//	// GET BY FE FIELD
//	// =========================================================
//
//	public ColumnMeta get(
//			String field
//	) {
//
//		if (
//				field == null
//						|| field.isBlank()
//		) {
//
//			throw new IllegalArgumentException(
//					"Fac Confirm filter field is required"
//			);
//		}
//
//
//		String apiField =
//				field.trim();
//
//
//		String dbField =
//				FIELD_MAPPING.get(
//						apiField
//				);
//
//
//		if (dbField == null) {
//
//			throw new IllegalArgumentException(
//					"Unsupported Fac Confirm filter field: "
//							+ field
//			);
//		}
//
//
//		ColumnMeta meta =
//				columns.get(
//						normalize(dbField)
//				);
//
//
//		if (meta == null) {
//
//			throw new IllegalArgumentException(
//					"Column not found in F2_Backlog_Main: "
//							+ dbField
//			);
//		}
//
//
//		return meta;
//	}
//
//
//	public boolean supports(
//			String field
//	) {
//
//		if (
//				field == null
//						|| field.isBlank()
//		) {
//			return false;
//		}
//
//
//		return FIELD_MAPPING.containsKey(
//				field.trim()
//		);
//	}
//
//
//	// =========================================================
//	// NORMALIZE
//	// =========================================================
//
//	private String normalize(
//			String value
//	) {
//
//		return value
//				.trim()
//				.toLowerCase(
//						Locale.ROOT
//				);
//	}
//
//
//	// =========================================================
//	// SQL TYPE
//	// =========================================================
//
//	private ColumnType resolveType(
//			String sqlType
//	) {
//
//		if (sqlType == null) {
//			return ColumnType.TEXT;
//		}
//
//
//		return switch (
//				sqlType.toLowerCase(
//						Locale.ROOT
//				)
//				) {
//
//			case "tinyint",
//			     "smallint",
//			     "int",
//			     "bigint",
//			     "decimal",
//			     "numeric",
//			     "float",
//			     "real",
//			     "money",
//			     "smallmoney",
//			     "bit" -> ColumnType.NUMBER;
//
//
//			case "date",
//			     "datetime",
//			     "datetime2",
//			     "smalldatetime",
//			     "datetimeoffset",
//			     "time" -> ColumnType.DATE;
//
//
//			default -> ColumnType.TEXT;
//		};
//	}
//
//
//	public enum ColumnType {
//		TEXT,
//		NUMBER,
//		DATE
//	}
//
//
//	public record ColumnMeta(
//			String name,
//			ColumnType type
//	) {
//	}
//}


package com.example.backlogbe.repository.facconfirm;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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