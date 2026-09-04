package com.example.backlogbe.repository.backlog;

import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
@RequiredArgsConstructor
public class BacklogMainRepository {

	private static final String SELECT_COLUMNS = """
			SELECT
			    VBELN,
			    ZGLOBAL_CODE,
			    PIER_AUFNR,
			    AUFNR,
			    IssueD,
			    ProductionD,
			    PromiseD,
			    ExportD,
			    ORG_Date,
			    MSM_Ship,
			    PNAME,
			    RRONYU1,
			    ShipBy,
			    GAMNG,
			    NETPR,
			    PHCD,
			    KWMENG,
			    RODENK,
			    LOEKZ,
			    MTO_ID,
			    PRT_ADDCMT1,
			    PRT_ADDCMT2,
			    PRT_STS,
			    Div,
			    FERTH,
			    PO_SRG_Convert,
			    ToDrill,
			    ToHeat,
			    ToPK,
			    Status,
			    CurrentProcess,
			    HeatCharge,
			    ProcessQty,
			    Z300Qty,
			    PkQty,
			    FinalQty,
			    TimeSQuenching,
			    TimeFHeat,
			    C_PRODH,
			    C_KEYCONTROL1,
			    C_KEYCONTROL3,
			    Updater,
			    UpdatedAt
			FROM F2_Backlog_Main
			""";
	private final JdbcTemplate jdbcTemplate;

	private final BacklogFilterSqlBuilder filterBuilder;

	private final BacklogColumnMetadataProvider metadataProvider;

	private final BacklogRowMapper rowMapper;


	// =========================================================
	// FIND FILTERED
	// =========================================================

	public List<BacklogMainDto> findFiltered(
			int page,
			int size,
			BacklogFilterRequest request,
			String sort
	) {

		int offset =
				page * size;


		var queryParts =
				filterBuilder.build(
						request
				);


		String orderBy =
				buildOrderBy(
						sort
				);


		String sql =
				SELECT_COLUMNS
						+ queryParts.where()
						+ orderBy
						+ """
						
						OFFSET ? ROWS
						FETCH NEXT ? ROWS ONLY
						""";


		List<Object> params =
				new ArrayList<>(
						queryParts.params()
				);

		params.add(offset);
		params.add(size);


		return jdbcTemplate.query(
				sql,
				rowMapper,
				params.toArray()
		);
	}


	// =========================================================
	// COUNT
	// =========================================================

	public long countFiltered(
			BacklogFilterRequest request
	) {

		var queryParts =
				filterBuilder.build(
						request
				);


		String sql = """
				SELECT COUNT_BIG(*)
				FROM F2_Backlog_Main
				"""
				+ queryParts.where();


		Long total =
				jdbcTemplate.queryForObject(
						sql,
						Long.class,
						queryParts.params()
								.toArray()
				);


		return total == null
				? 0L
				: total;
	}


	// =========================================================
	// EXCEL FILTER DISTINCT VALUES
	// =========================================================
	private boolean isHighCardinalityField(
			String field
	) {
		return switch (field) {
			case "VBELN",
			     "ZGLOBAL_CODE",
			     "PIER_AUFNR",
			     "AUFNR",
			     "PNAME",
			     "RRONYU1",
			     "MTO_ID",
			     "HeatCharge",
			     "Updater" -> true;

			default -> false;
		};
	}

	public List<String> findDistinctValues(
			String field,
			String search,
			int limit,
			BacklogFilterRequest activeFilters
	) {

		// =========================================================
		// COLUMN
		// =========================================================

		String column =
				resolveColumn(
						field
				);


		// =========================================================
		// COLUMN TYPE FROM DATABASE METADATA
		// =========================================================

		BacklogColumnMetadataProvider.ColumnMeta columnMeta =
				metadataProvider.get(
						field
				);


		boolean dateField =
				columnMeta.type()
						== BacklogColumnMetadataProvider.ColumnType.DATE;


		// =========================================================
		// SAFE LIMIT
		// =========================================================

		int safeLimit =
				Math.min(
						Math.max(
								limit,
								1
						),
						500
				);


		String safeSearch =
				search == null
						? ""
						: search.trim();


		// =========================================================
		// REMOVE CURRENT COLUMN FILTER
		// =========================================================

		BacklogFilterRequest otherFilters =
				removeCurrentFieldFilter(
						activeFilters,
						field
				);


		// =========================================================
		// OTHER ACTIVE FILTERS
		// =========================================================

		var queryParts =
				filterBuilder.build(
						otherFilters
				);


		List<String> conditions =
				new ArrayList<>();


		List<Object> params =
				new ArrayList<>(
						queryParts.params()
				);


		if (
				queryParts.where() != null
						&& !queryParts.where().isBlank()
		) {

			String where =
					queryParts.where()
							.trim();


			if (
					where.regionMatches(
							true,
							0,
							"WHERE ",
							0,
							6
					)
			) {

				where =
						where.substring(
								6
						);
			}


			conditions.add(
					"("
							+ where
							+ ")"
			);
		}


		// =========================================================
		// FILTER OPTION VALUE
		//
		// DATE:
		//
		// DB:
		// 2026-08-01 04:26:00
		// 2026-08-01 20:44:00
		//
		// OPTION:
		// 2026-08-01
		// =========================================================

		String valueExpression =
				buildFilterValueExpression(
						column,
						dateField
				);


		// =========================================================
		// SEARCH
		// =========================================================

		if (
				!safeSearch.isBlank()
		) {

			if (dateField) {

				conditions.add(
						valueExpression
								+ " LIKE ?"
				);

			} else {

				conditions.add(
						"CAST("
								+ column
								+ " AS NVARCHAR(500)) LIKE ?"
				);
			}


			params.add(
					"%"
							+ safeSearch
							+ "%"
			);
		}


		// =========================================================
		// WHERE
		// =========================================================

		String whereSql =
				conditions.isEmpty()
						? ""
						: " WHERE "
						+ String.join(
						" AND ",
						conditions
				);


		// =========================================================
		// HIGH CARDINALITY
		// =========================================================

		if (
				isHighCardinalityField(
						field
				)
		) {

			String sql = """
					SELECT DISTINCT TOP (%d)
					
					    %s AS FilterValue
					
					FROM F2_Backlog_Main
					
					%s
					
					ORDER BY
					    FilterValue
					""".formatted(
					safeLimit,
					valueExpression,
					whereSql
			);


			return jdbcTemplate.query(
					sql,

					(rs, rowNum) ->
							rs.getString(
									"FilterValue"
							),

					params.toArray()
			);
		}


		// =========================================================
		// LOW CARDINALITY / DATE
		// =========================================================

		String sql = """
				SELECT DISTINCT
				
				    %s AS FilterValue
				
				FROM F2_Backlog_Main
				
				%s
				
				ORDER BY
				    FilterValue
				""".formatted(
				valueExpression,
				whereSql
		);


		return jdbcTemplate.query(
				sql,

				(rs, rowNum) ->
						rs.getString(
								"FilterValue"
						),

				params.toArray()
		);
	}

	private BacklogFilterRequest removeCurrentFieldFilter(
			BacklogFilterRequest request,
			String currentField
	) {

		if (
				request == null
						|| request.filters() == null
						|| request.filters().isEmpty()
		) {

			return new BacklogFilterRequest(
					List.of(),
					"and"
			);
		}


		List<com.example.backlogbe.dto.backlog.BacklogFilterItem>
				filters =
				request.filters()
						.stream()

						// bỏ null
						.filter(
								filter ->
										filter != null
						)

						// bỏ filter của chính column đang mở
						.filter(
								filter ->
										filter.field() == null
												|| !filter.field()
												.equalsIgnoreCase(
														currentField
												)
						)

						.toList();


		return new BacklogFilterRequest(
				filters,
				request.logicOperator()
		);
	}
	// =========================================================
	// ORDER BY
	// =========================================================

	private String buildOrderBy(
			String sort
	) {

		// default sort
		if (
				sort == null
						|| sort.isBlank()
		) {

			return """
					
					ORDER BY
					    UpdatedAt DESC,
					    VBELN DESC
					""";
		}


		String[] parts =
				sort.split(
						",",
						2
				);


		if (
				parts.length != 2
		) {

			return """
					
					ORDER BY
					    UpdatedAt DESC,
					    VBELN DESC
					""";
		}


		String field =
				parts[0].trim();

		String direction =
				parts[1]
						.trim()
						.toLowerCase(
								Locale.ROOT
						);


		String column =
				resolveColumn(
						field
				);


		String safeDirection =
				switch (direction) {

					case "asc" -> "ASC";

					case "desc" -> "DESC";

					default -> throw new IllegalArgumentException(
							"Unsupported sort direction: "
									+ direction
					);
				};


		return """
				
				ORDER BY %s %s
				""".formatted(
				column,
				safeDirection
		);
	}


	// =========================================================
	// COLUMN WHITELIST
	// =========================================================

	private String resolveColumn(
			String field
	) {

		if (
				field == null
						|| field.isBlank()
		) {

			throw new IllegalArgumentException(
					"Backlog field is required"
			);
		}


		return switch (
				field.trim()
				) {

			case "VBELN" -> "VBELN";

			case "ZGLOBAL_CODE" -> "ZGLOBAL_CODE";

			case "PIER_AUFNR" -> "PIER_AUFNR";

			case "AUFNR" -> "AUFNR";

			case "IssueD" -> "IssueD";

			case "ProductionD" -> "ProductionD";

			case "PromiseD" -> "PromiseD";

			case "ExportD" -> "ExportD";

			case "ORG_Date" -> "ORG_Date";

			case "MSM_Ship" -> "MSM_Ship";

			case "PNAME" -> "PNAME";

			case "RRONYU1" -> "RRONYU1";

			case "ShipBy" -> "ShipBy";

			case "GAMNG" -> "GAMNG";

			case "NETPR" -> "NETPR";

			case "PHCD" -> "PHCD";

			case "KWMENG" -> "KWMENG";

			case "RODENK" -> "RODENK";

			case "LOEKZ" -> "LOEKZ";

			case "MTO_ID" -> "MTO_ID";

			case "PRT_ADDCMT1" -> "PRT_ADDCMT1";

			case "PRT_ADDCMT2" -> "PRT_ADDCMT2";

			case "PRT_STS" -> "PRT_STS";

			case "Div" -> "Div";

			case "FERTH" -> "FERTH";

			case "PO_SRG_Convert" -> "PO_SRG_Convert";

			case "ToDrill" -> "ToDrill";

			case "ToHeat" -> "ToHeat";

			case "ToPK" -> "ToPK";

			case "Status" -> "Status";

			case "CurrentProcess" -> "CurrentProcess";

			case "HeatCharge" -> "HeatCharge";

			case "ProcessQty" -> "ProcessQty";

			case "Z300Qty" -> "Z300Qty";

			case "PkQty" -> "PkQty";

			case "FinalQty" -> "FinalQty";

			case "TimeSQuenching" -> "TimeSQuenching";

			case "TimeFHeat" -> "TimeFHeat";

			case "C_PRODH" -> "C_PRODH";

			case "C_KEYCONTROL1" -> "C_KEYCONTROL1";

			case "C_KEYCONTROL3" -> "C_KEYCONTROL3";

			case "Updater" -> "Updater";

			case "UpdatedAt" -> "UpdatedAt";

			default -> throw new IllegalArgumentException(
					"Unsupported backlog field: "
							+ field
			);
		};
	}

	private String buildFilterValueExpression(
			String column,
			boolean dateField
	) {

		if (dateField) {

			/*
			 * CHỈ dùng cho danh sách Excel Filter.
			 *
			 * DB:
			 * 2026-08-01 04:26:00
			 *
			 * Filter option:
			 * 2026-08-01
			 *
			 * Không UPDATE hoặc thay đổi dữ liệu DB.
			 */
			return """
					CASE
					    WHEN %s IS NULL THEN ''
					    ELSE CONVERT(
					        VARCHAR(10),
					        %s,
					        23
					    )
					END
					""".formatted(
					column,
					column
			);
		}


		return """
				COALESCE(
				    CAST(
				        %s
				        AS NVARCHAR(500)
				    ),
				    ''
				)
				""".formatted(
				column
		);
	}


}