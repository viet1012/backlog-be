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

	private final JdbcTemplate jdbcTemplate;

	private final BacklogFilterSqlBuilder filterBuilder;

	private final BacklogRowMapper rowMapper;


	// =========================================================
	// SELECT
	// =========================================================

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

		String column =
				resolveColumn(field);

		int safeLimit =
				Math.min(
						Math.max(limit, 1),
						500
				);

		String safeSearch =
				search == null
						? ""
						: search.trim();


		// =========================================================
		// 1. LOẠI FILTER CỦA CHÍNH COLUMN ĐANG MỞ
		// =========================================================
		//
		// Ví dụ:
		//
		// Status = WIP
		// Div    = PR
		//
		// Khi mở filter ShipBy:
		//      giữ Status + Div
		//
		// Khi mở lại filter Status:
		//      bỏ Status
		//      giữ Div
		//
		// Đây là behavior giống Excel.
		// =========================================================

		BacklogFilterRequest otherFilters =
				removeCurrentFieldFilter(
						activeFilters,
						field
				);


		// =========================================================
		// 2. BUILD WHERE TỪ CÁC FILTER KHÁC
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


		// queryParts.where() dạng:
		//
		// " WHERE [Status] = ? AND [Div] = ?"
		//
		// Ta lấy phần condition ra để có thể append search.

		if (
				queryParts.where() != null
						&& !queryParts.where().isBlank()
		) {

			String where =
					queryParts.where().trim();

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
						where.substring(6);
			}

			conditions.add(
					"(" + where + ")"
			);
		}


		// =========================================================
		// 3. SEARCH TRONG COLUMN ĐANG MỞ
		// =========================================================

		if (!safeSearch.isBlank()) {

			conditions.add(
					"CAST(" +
							column +
							" AS NVARCHAR(500)) LIKE ?"
			);

			params.add(
					"%" + safeSearch + "%"
			);
		}


		// =========================================================
		// 4. BUILD WHERE
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
		// 5. HIGH CARDINALITY
		//
		// VBELN / AUFNR / PNAME...
		//
		// Chỉ lấy TOP N
		// =========================================================

		if (isHighCardinalityField(field)) {

			String sql = """
					SELECT DISTINCT TOP (%d)
					    COALESCE(
					        CAST(%s AS NVARCHAR(500)),
					        ''
					    ) AS FilterValue
					FROM F2_Backlog_Main
					%s
					ORDER BY FilterValue
					""".formatted(
					safeLimit,
					column,
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
		// 6. LOW CARDINALITY
		//
		// Status / Div / ShipBy / CurrentProcess...
		//
		// Không limit vì số lượng distinct nhỏ.
		// Nhưng PHẢI áp dụng active filters.
		// =========================================================

		String sql = """
				SELECT DISTINCT
				    COALESCE(
				        CAST(%s AS NVARCHAR(500)),
				        ''
				    ) AS FilterValue
				FROM F2_Backlog_Main
				%s
				ORDER BY FilterValue
				""".formatted(
				column,
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
}