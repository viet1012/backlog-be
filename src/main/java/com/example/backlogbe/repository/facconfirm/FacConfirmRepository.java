package com.example.backlogbe.repository.facconfirm;

import com.example.backlogbe.dto.facconfirm.FacConfirmDto;
import com.example.backlogbe.dto.facconfirm.FacConfirmFilterItem;
import com.example.backlogbe.dto.facconfirm.FacConfirmProcessGroupDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;


@Repository
@RequiredArgsConstructor
public class FacConfirmRepository {


	private static final String DETAIL_SELECT = """
			SELECT
			    bl.FERTH,
			    bl.ProductGrp,
			    bl.AUFNR,
			    bl.ZGLOBAL_CODE,
			    bl.PNAME,
			    bl.IssueD,
			    bl.ExportD,
			    bl.RRONYU1 AS CusId,
			    bl.ShipBy,
			    bl.MTO_ID,
			    bl.PRT_ADDCMT2,
			    bl.CurrentProcess,
			    bl.FinalQty,
			    bl.ToDrill,
			    bl.ToHeat,
			    bl.TimeSQuenching AS Heat_Start,
			    bl.TimeFHeat AS Heat_Finish,
			    bl.ToPK
			
			FROM F2_Backlog_Main bl
			""";
	private final JdbcTemplate jdbcTemplate;
	private final FacConfirmFilterSqlBuilder filterBuilder;
	private final FacConfirmColumnMetadataProvider metadataProvider;


	// =========================================================
	// DETAIL SELECT
	// =========================================================
	private final FacConfirmRowMapper rowMapper;


	// =========================================================
	// BASE WHERE
	// =========================================================

	private String buildBaseWhere(
			String div,
			LocalDate expD,
			String procGrp,
			List<Object> params
	) {

		params.add(
				Timestamp.valueOf(
						expD.atStartOfDay()
				)
		);


		// procGrp xuất hiện 3 lần trong SQL

		params.add(procGrp);
		params.add(procGrp);
		params.add(procGrp);


		// div xuất hiện 2 lần

		params.add(div);
		params.add(div);


		return """
				WHERE bl.ExportD <= ?
				
				  AND (
				         (? = 'Fine'
				             AND bl.ProcessGrp2 IN (
				                 'Fine',
				                 'Heat',
				                 'Rough'
				             )
				         )
				
				      OR (? = 'Heat'
				             AND bl.ProcessGrp2 IN (
				                 'Heat',
				                 'Rough'
				             )
				         )
				
				      OR (? = 'Rough'
				             AND bl.ProcessGrp2 = 'Rough'
				         )
				  )
				
				  AND (
				         bl.Div = ?
				
				      OR (
				             ? = 'GU'
				             AND bl.Div LIKE '%G'
				         )
				  )
				""";
	}


	// =========================================================
	// FIND PAGE
	// OLD GET API
	// =========================================================

	public List<FacConfirmDto> findPage(
			String div,
			LocalDate expD,
			String procGrp,
			int page,
			int size
	) {

		int offset =
				page * size;


		List<Object> params =
				new ArrayList<>();


		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						params
				);


		String sql =
				DETAIL_SELECT
						+ baseWhere
						+ """
						
						ORDER BY
						    bl.ExportD,
						    bl.ProductGrp,
						    bl.AUFNR
						
						OFFSET ? ROWS
						FETCH NEXT ? ROWS ONLY
						""";


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
	// OLD GET API
	// =========================================================

	public long count(
			String div,
			LocalDate expD,
			String procGrp
	) {

		List<Object> params =
				new ArrayList<>();


		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						params
				);


		String sql = """
				SELECT COUNT_BIG(*)
				FROM F2_Backlog_Main bl
				"""
				+ baseWhere;


		Long total =
				jdbcTemplate.queryForObject(
						sql,
						Long.class,
						params.toArray()
				);


		return total == null
				? 0L
				: total;
	}


	// =========================================================
	// SEARCH
	// EXCEL FILTER
	// =========================================================

	public List<FacConfirmDto> search(
			String div,
			LocalDate expD,
			String procGrp,
			int page,
			int size,
			List<FacConfirmFilterItem> filters,
			String logicOperator
	) {

		int offset =
				page * size;


		List<Object> params =
				new ArrayList<>();


		// =====================================================
		// BASE PARAMS
		// =====================================================

		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						params
				);


		// =====================================================
		// EXCEL FILTER
		// =====================================================

		FacConfirmFilterSqlBuilder.QueryParts filterParts =
				filterBuilder.build(
						filters,
						logicOperator
				);


		params.addAll(
				filterParts.params()
		);


		// =====================================================
		// PAGINATION
		// =====================================================

		params.add(offset);
		params.add(size);


		String sql =
				DETAIL_SELECT
						+ baseWhere
						+ filterParts.sql()
						+ """
						
						ORDER BY
						    bl.ExportD,
						    bl.ProductGrp,
						    bl.AUFNR
						
						OFFSET ? ROWS
						FETCH NEXT ? ROWS ONLY
						""";


		return jdbcTemplate.query(
				sql,
				rowMapper,
				params.toArray()
		);
	}


	// =========================================================
	// COUNT SEARCH
	// =========================================================

	public long countSearch(
			String div,
			LocalDate expD,
			String procGrp,
			List<FacConfirmFilterItem> filters,
			String logicOperator
	) {

		List<Object> params =
				new ArrayList<>();


		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						params
				);


		FacConfirmFilterSqlBuilder.QueryParts filterParts =
				filterBuilder.build(
						filters,
						logicOperator
				);


		params.addAll(
				filterParts.params()
		);


		String sql = """
				SELECT COUNT_BIG(*)
				FROM F2_Backlog_Main bl
				"""
				+ baseWhere
				+ filterParts.sql();


		Long total =
				jdbcTemplate.queryForObject(
						sql,
						Long.class,
						params.toArray()
				);


		return total == null
				? 0L
				: total;
	}


	// =========================================================
	// FILTER OPTIONS
	// =========================================================

	public List<String> findFilterOptions(
			String field,
			String search,
			String div,
			LocalDate expD,
			String procGrp,
			List<FacConfirmFilterItem> filters
	) {

		// =====================================================
		// SAFE COLUMN
		// =====================================================

		FacConfirmColumnMetadataProvider.ColumnMeta columnMeta =
				metadataProvider.get(
						field
				);


		String column =
				"["
						+ columnMeta.name()
						+ "]";


		// =====================================================
		// REMOVE FILTER OF CURRENT FIELD
		//
		// Ví dụ:
		//
		// đang mở CurrentProcess
		//
		// CurrentProcess = SG
		// ShipBy = AIR
		//
		// Khi load options CurrentProcess:
		//
		// giữ ShipBy = AIR
		// bỏ CurrentProcess = SG
		//
		// giống Excel
		// =====================================================

		List<FacConfirmFilterItem> otherFilters =
				filters == null
						? List.of()
						: filters.stream()

						.filter(
								item ->
										item != null
						)

						.filter(
								item ->
										item.field() == null
												|| !field.equalsIgnoreCase(
												item.field()
										)
						)

						.toList();


		// =====================================================
		// PARAMS
		// =====================================================

		List<Object> params =
				new ArrayList<>();


		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						params
				);


		// =====================================================
		// OTHER FILTERS
		// =====================================================

		FacConfirmFilterSqlBuilder.QueryParts filterParts =
				filterBuilder.build(
						otherFilters,
						"and"
				);


		params.addAll(
				filterParts.params()
		);


		// =====================================================
		// SQL
		// =====================================================

		StringBuilder sql =
				new StringBuilder();


		sql.append("""
				SELECT DISTINCT
				
				    ISNULL(
				        CAST(
				""");


		sql.append(
				column
		);


		sql.append("""
				            AS NVARCHAR(500)
				        ),
				        ''
				    ) AS FilterValue
				
				FROM F2_Backlog_Main bl
				""");


		sql.append(
				baseWhere
		);


		sql.append(
				filterParts.sql()
		);


		// =====================================================
		// SEARCH INSIDE FILTER OPTIONS
		// =====================================================

		if (
				search != null
						&& !search.isBlank()
		) {

			sql.append(
					" AND ISNULL(CAST("
							+ column
							+ " AS NVARCHAR(500)), '') LIKE ? "
			);


			params.add(
					"%"
							+ search.trim()
							+ "%"
			);
		}


		// =====================================================
		// ORDER
		// =====================================================

		sql.append("""
				
				ORDER BY FilterValue
				""");


		return jdbcTemplate.query(
				sql.toString(),

				(rs, rowNum) ->
						rs.getString(
								"FilterValue"
						),

				params.toArray()
		);
	}


	// =========================================================
	// PROCESS GROUP SUMMARY
	// =========================================================

	public List<FacConfirmProcessGroupDto> findProcessGroups(
			String div,
			LocalDate expD
	) {

		/*
		 * Business hierarchy:
		 *
		 * Rough:
		 *   ProcessGrp2 = Rough
		 *
		 * Heat:
		 *   ProcessGrp2 IN (Heat, Rough)
		 *
		 * Fine:
		 *   ProcessGrp2 IN (Fine, Heat, Rough)
		 *
		 * Display:
		 * Rough -> Heat -> Fine
		 */


		String sql = """
				WITH Base AS (
				
				    SELECT
				        bl.ProcessGrp2,
				
				        ISNULL(
				            bl.FinalQty,
				            0
				        ) AS FinalQty
				
				    FROM F2_Backlog_Main bl
				
				    WHERE bl.ExportD <= ?
				
				      AND (
				             bl.Div = ?
				
				          OR (
				                 ? = 'GU'
				                 AND bl.Div LIKE '%G'
				             )
				      )
				
				      AND bl.ProcessGrp2 IN (
				          'Fine',
				          'Heat',
				          'Rough'
				      )
				),
				
				Summary AS (
				
				    -- =========================================
				    -- ROUGH
				    -- =========================================
				
				    SELECT
				        'Rough' AS ProcessGroup,
				
				        1 AS SortOrder,
				
				        COUNT_BIG(*) AS OrderCount,
				
				        SUM(
				            CAST(
				                FinalQty
				                AS DECIMAL(18, 2)
				            )
				        ) AS TotalFinalQty
				
				    FROM Base
				
				    WHERE ProcessGrp2 = 'Rough'
				
				
				    UNION ALL
				
				
				    -- =========================================
				    -- HEAT
				    -- =========================================
				
				    SELECT
				        'Heat' AS ProcessGroup,
				
				        2 AS SortOrder,
				
				        COUNT_BIG(*) AS OrderCount,
				
				        SUM(
				            CAST(
				                FinalQty
				                AS DECIMAL(18, 2)
				            )
				        ) AS TotalFinalQty
				
				    FROM Base
				
				    WHERE ProcessGrp2 IN (
				        'Heat',
				        'Rough'
				    )
				
				
				    UNION ALL
				
				
				    -- =========================================
				    -- FINE
				    -- =========================================
				
				    SELECT
				        'Fine' AS ProcessGroup,
				
				        3 AS SortOrder,
				
				        COUNT_BIG(*) AS OrderCount,
				
				        SUM(
				            CAST(
				                FinalQty
				                AS DECIMAL(18, 2)
				            )
				        ) AS TotalFinalQty
				
				    FROM Base
				)
				
				SELECT
				    ProcessGroup,
				    OrderCount,
				
				    ISNULL(
				        TotalFinalQty,
				        0
				    ) AS TotalFinalQty
				
				FROM Summary
				
				ORDER BY SortOrder
				""";


		List<Object> params =
				new ArrayList<>();


		params.add(
				Timestamp.valueOf(
						expD.atStartOfDay()
				)
		);


		params.add(div);
		params.add(div);


		return jdbcTemplate.query(

				sql,

				(rs, rowNum) ->
						new FacConfirmProcessGroupDto(

								rs.getString(
										"ProcessGroup"
								),

								rs.getLong(
										"OrderCount"
								),

								rs.getBigDecimal(
										"TotalFinalQty"
								)
						),

				params.toArray()
		);
	}
}