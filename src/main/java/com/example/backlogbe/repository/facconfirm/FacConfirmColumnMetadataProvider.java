package com.example.backlogbe.repository.facconfirm;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;


@Component
@RequiredArgsConstructor
public class FacConfirmColumnMetadataProvider {

	private static final Map<String, String> FIELD_MAPPING =
			Map.ofEntries(

					Map.entry(
							"ferth",
							"FERTH"
					),

					Map.entry(
							"productGrp",
							"ProductGrp"
					),

					Map.entry(
							"aufnr",
							"AUFNR"
					),

					Map.entry(
							"zglobalCode",
							"ZGLOBAL_CODE"
					),

					Map.entry(
							"pname",
							"PNAME"
					),

					Map.entry(
							"issueD",
							"IssueD"
					),

					Map.entry(
							"exportD",
							"ExportD"
					),

					Map.entry(
							"cusId",
							"RRONYU1"
					),

					Map.entry(
							"shipBy",
							"ShipBy"
					),

					Map.entry(
							"mtoId",
							"MTO_ID"
					),

					Map.entry(
							"prtAddcmt2",
							"PRT_ADDCMT2"
					),

					Map.entry(
							"currentProcess",
							"CurrentProcess"
					),

					Map.entry(
							"finalQty",
							"FinalQty"
					),

					Map.entry(
							"toDrill",
							"ToDrill"
					),

					Map.entry(
							"toHeat",
							"ToHeat"
					),

					Map.entry(
							"heatStart",
							"TimeSQuenching"
					),

					Map.entry(
							"heatFinish",
							"TimeFHeat"
					),

					Map.entry(
							"toPk",
							"ToPK"
					)
			);
	private final JdbcTemplate jdbcTemplate;


	// =========================================================
	// FAC CONFIRM FIELD -> DB FIELD
	// =========================================================
	private final Map<String, ColumnMeta> columns =
			new HashMap<>();


	// =========================================================
	// LOAD METADATA
	// =========================================================

	@PostConstruct
	public void load() {

		columns.clear();


		jdbcTemplate.query(
				"""
						SELECT TOP 0 *
						FROM F2_Backlog_Main
						""",

				(ResultSetExtractor<Void>) rs -> {

					ResultSetMetaData meta =
							rs.getMetaData();


					for (
							int i = 1;
							i <= meta.getColumnCount();
							i++
					) {

						String name =
								meta.getColumnName(i);


						String sqlType =
								meta.getColumnTypeName(i);


						columns.put(
								normalize(name),

								new ColumnMeta(
										name,
										resolveType(
												sqlType
										)
								)
						);
					}


					return null;
				}
		);


		if (columns.isEmpty()) {

			throw new IllegalStateException(
					"Unable to load F2_Backlog_Main metadata"
			);
		}
	}


	// =========================================================
	// GET BY FE FIELD
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


		String apiField =
				field.trim();


		String dbField =
				FIELD_MAPPING.get(
						apiField
				);


		if (dbField == null) {

			throw new IllegalArgumentException(
					"Unsupported Fac Confirm filter field: "
							+ field
			);
		}


		ColumnMeta meta =
				columns.get(
						normalize(dbField)
				);


		if (meta == null) {

			throw new IllegalArgumentException(
					"Column not found in F2_Backlog_Main: "
							+ dbField
			);
		}


		return meta;
	}


	public boolean supports(
			String field
	) {

		if (
				field == null
						|| field.isBlank()
		) {
			return false;
		}


		return FIELD_MAPPING.containsKey(
				field.trim()
		);
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
	// SQL TYPE
	// =========================================================

	private ColumnType resolveType(
			String sqlType
	) {

		if (sqlType == null) {
			return ColumnType.TEXT;
		}


		return switch (
				sqlType.toLowerCase(
						Locale.ROOT
				)
				) {

			case "tinyint",
			     "smallint",
			     "int",
			     "bigint",
			     "decimal",
			     "numeric",
			     "float",
			     "real",
			     "money",
			     "smallmoney",
			     "bit" -> ColumnType.NUMBER;


			case "date",
			     "datetime",
			     "datetime2",
			     "smalldatetime",
			     "datetimeoffset",
			     "time" -> ColumnType.DATE;


			default -> ColumnType.TEXT;
		};
	}


	public enum ColumnType {
		TEXT,
		NUMBER,
		DATE
	}


	public record ColumnMeta(
			String name,
			ColumnType type
	) {
	}
}