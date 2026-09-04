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


	private static final String FAC_DATA_CTE = """
			
			WITH LatestConfirm AS (
			
			    SELECT
			        fc.AUNFR,
			        fc.ProcessGrp,
			        fc.ConfirmFnTime,
			
			        ROW_NUMBER() OVER (
			
			            PARTITION BY
			                fc.AUNFR,
			                fc.ProcessGrp
			
			            ORDER BY
			                fc.UpdatedAt DESC
			
			        ) AS rn
			
			    FROM F2Database.dbo.F2_Backlog_Fac_Confirm fc
			
			    WHERE fc.ConfirmFnTime IS NOT NULL
			
			      AND fc.ProcessGrp IN (
			          'To Drill',
			          'To Heat',
			          'Heat Start',
			          'Heat Finish',
			          'To Packing'
			      )
			),
			
			
			ConfirmPivot AS (
			
			    SELECT
			        AUNFR,
			
			
			        MAX(
			            CASE
			                WHEN ProcessGrp = 'To Drill'
			                THEN ConfirmFnTime
			            END
			        ) AS Confirm_ToDrill,
			
			
			        MAX(
			            CASE
			                WHEN ProcessGrp = 'To Heat'
			                THEN ConfirmFnTime
			            END
			        ) AS Confirm_ToHeat,
			
			
			        MAX(
			            CASE
			                WHEN ProcessGrp = 'Heat Start'
			                THEN ConfirmFnTime
			            END
			        ) AS Confirm_HeatStart,
			
			
			        MAX(
			            CASE
			                WHEN ProcessGrp = 'Heat Finish'
			                THEN ConfirmFnTime
			            END
			        ) AS Confirm_HeatFinish,
			
			
			        MAX(
			            CASE
			                WHEN ProcessGrp = 'To Packing'
			                THEN ConfirmFnTime
			            END
			        ) AS Confirm_ToPacking
			
			
			    FROM LatestConfirm
			
			    WHERE rn = 1
			
			    GROUP BY
			        AUNFR
			),
			
			
			FacData AS (
			
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
			
			        -- needed for business filtering
			        bl.ProcessGrp2,
			        bl.Div,
			
			
			        -- =========================================
			        -- TO DRILL
			        -- Confirm exists -> use ConfirmFnTime
			        -- Otherwise     -> use backlog value
			        -- =========================================
			        COALESCE(
			            cp.Confirm_ToDrill,
			            bl.ToDrill
			        ) AS ToDrill,
			
			
			        -- =========================================
			        -- TO HEAT
			        -- =========================================
			        COALESCE(
			            cp.Confirm_ToHeat,
			            bl.ToHeat
			        ) AS ToHeat,
			
			
			        -- =========================================
			        -- HEAT START
			        -- =========================================
			        COALESCE(
			            cp.Confirm_HeatStart,
			            bl.TimeSQuenching
			        ) AS Heat_Start,
			
			
			        -- =========================================
			        -- HEAT FINISH
			        -- =========================================
			        COALESCE(
			            cp.Confirm_HeatFinish,
			            bl.TimeFHeat
			        ) AS Heat_Finish,
			
			
			        -- =========================================
			        -- TO PACKING
			        -- =========================================
			        COALESCE(
			            cp.Confirm_ToPacking,
			            bl.ToPK
			        ) AS ToPK
			
			
			    FROM F2_Backlog_Main bl
			
			    LEFT JOIN ConfirmPivot cp
			        ON cp.AUNFR = bl.AUFNR
			)
			
			""";

	private static final String DETAIL_COLUMNS = """
			
			SELECT
			
			    d.FERTH,
			
			    d.ProductGrp,
			
			    d.AUFNR,
			
			    d.ZGLOBAL_CODE,
			
			    d.PNAME,
			
			    d.IssueD,
			
			    d.ExportD,
			
			    d.CusId,
			
			    d.ShipBy,
			
			    d.MTO_ID,
			
			    d.PRT_ADDCMT2,
			
			    d.CurrentProcess,
			
			    d.FinalQty,
			
			    d.ToDrill,
			
			    d.ToHeat,
			
			    d.Heat_Start,
			
			    d.Heat_Finish,
			
			    d.ToPK
			
			FROM FacData d
			
			""";
	private final JdbcTemplate jdbcTemplate;
	private final FacConfirmFilterSqlBuilder filterBuilder;


	// =========================================================
	// COMMON DATA SOURCE
	//
	// F2_Backlog_Main
	//
	//          +
	//
	// F2_Backlog_Fac_Confirm
	//
	//          ↓
	//
	// FacData
	//
	// Đây là nguồn duy nhất cho:
	//
	// - detail
	// - search
	// - count
	// - Excel filter
	//
	// =========================================================
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

		// =====================================================
		// EXPORT DATE
		// =====================================================

		params.add(
				Timestamp.valueOf(
						expD.atStartOfDay()
				)
		);


		// =====================================================
		// PROCESS GROUP
		//
		// dùng 3 lần trong SQL
		// =====================================================

		params.add(procGrp);

		params.add(procGrp);

		params.add(procGrp);


		// =====================================================
		// DIV
		//
		// dùng 2 lần
		// =====================================================

		params.add(div);

		params.add(div);


		return """
				
				WHERE d.ExportD <= ?
				
				  AND (
				
				         (
				             ? = 'Fine'
				
				             AND d.ProcessGrp2 IN (
				                 'Fine',
				                 'Heat',
				                 'Rough'
				             )
				         )
				
				
				      OR (
				
				             ? = 'Heat'
				
				             AND d.ProcessGrp2 IN (
				                 'Heat',
				                 'Rough'
				             )
				         )
				
				
				      OR (
				
				             ? = 'Rough'
				
				             AND d.ProcessGrp2 = 'Rough'
				         )
				  )
				
				
				  AND (
				
				         d.Div = ?
				
				      OR (
				
				             ? = 'GU'
				
				             AND d.Div LIKE '%G'
				         )
				  )
				
				""";
	}


	// =========================================================
	// FIND PAGE
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
				FAC_DATA_CTE

						+ DETAIL_COLUMNS

						+ baseWhere

						+ """
						
						ORDER BY
						
						    d.ExportD,
						
						    d.ProductGrp,
						
						    d.AUFNR
						
						
						OFFSET ? ROWS
						
						FETCH NEXT ? ROWS ONLY
						
						""";


		params.add(
				offset
		);


		params.add(
				size
		);


		return jdbcTemplate.query(
				sql,
				rowMapper,
				params.toArray()
		);
	}


	// =========================================================
	// COUNT
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


		String sql =
				FAC_DATA_CTE

						+ """
						
						SELECT
						    COUNT_BIG(*)
						
						FROM FacData d
						
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
		// BASE
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
		// SQL
		// =====================================================

		String sql =
				FAC_DATA_CTE

						+ DETAIL_COLUMNS

						+ baseWhere

						+ filterParts.sql()

						+ """
						
						ORDER BY
						
						    d.ExportD,
						
						    d.ProductGrp,
						
						    d.AUFNR
						
						
						OFFSET ? ROWS
						
						FETCH NEXT ? ROWS ONLY
						
						""";


		// =====================================================
		// PAGINATION
		// =====================================================

		params.add(
				offset
		);


		params.add(
				size
		);


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


		String sql =
				FAC_DATA_CTE

						+ """
						
						SELECT
						    COUNT_BIG(*)
						
						FROM FacData d
						
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
		// COLUMN
		// =====================================================

		FacConfirmColumnMetadataProvider.ColumnMeta meta =
				metadataProvider.get(
						field
				);


		String column =
				"["
						+ meta.name()
						+ "]";


		boolean dateField =
				meta.type()
						== FacConfirmColumnMetadataProvider.ColumnType.DATE;


		// =====================================================
		// DISPLAY VALUE
		//
		// DATE:
		//
		// DB:
		// 2026-09-03 14:26:37
		//
		// Excel option:
		// 2026-09-03
		//
		// DB DATA KHÔNG BỊ THAY ĐỔI.
		// =====================================================

		String valueExpression;


		if (dateField) {

			valueExpression =
					"""
							
							CASE
							
								WHEN %s IS NULL
								THEN ''
							
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

		} else {

			valueExpression =
					"""
							
							ISNULL(
							
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


		// =====================================================
		// REMOVE CURRENT FILTER
		//
		// Excel behavior
		// =====================================================

		List<FacConfirmFilterItem> otherFilters =
				filters == null
						? List.of()
						: filters
						.stream()

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


		sql.append(
				FAC_DATA_CTE
		);


		sql.append(
				"""
						
						SELECT DISTINCT
						
							%s AS FilterValue
						
						FROM FacData d
						
						""".formatted(
						valueExpression
				)
		);


		sql.append(
				baseWhere
		);


		sql.append(
				filterParts.sql()
		);


		// =====================================================
		// SEARCH
		// =====================================================

		String safeSearch =
				search == null
						? ""
						: search.trim();


		if (
				!safeSearch.isBlank()
		) {

			sql.append(
					" AND "
							+ valueExpression
							+ " LIKE ? "
			);


			params.add(
					"%"
							+ safeSearch
							+ "%"
			);
		}


		// =====================================================
		// ORDER
		// =====================================================

		sql.append(
				"""
						
						ORDER BY
							FilterValue
						
						"""
		);


		// =====================================================
		// RESULT
		// =====================================================

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
		 * Summary business hiện tại:
		 *
		 * Rough:
		 * final confirm = To Heat
		 *
		 * Heat:
		 * final confirm = Heat Finish
		 *
		 * Fine:
		 * final confirm = To Packing
		 *
		 * Required:
		 *
		 * final backlog column NULL
		 * OR
		 * đã từng có Fac Confirm record
		 */

		String sql = """
				
				WITH Base AS (
				
				    SELECT
				
				        bl.AUFNR,
				
				        bl.ProcessGrp2,
				
				        ISNULL(
				            bl.FinalQty,
				            0
				        ) AS FinalQty,
				
				        bl.ToHeat,
				
				        bl.TimeFHeat,
				
				        bl.ToPK
				
				
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
				
				
				Confirmed AS (
				
				    SELECT DISTINCT
				
				        fc.AUNFR,
				
				        fc.ProcessGrp
				
				
				    FROM F2Database.dbo.F2_Backlog_Fac_Confirm fc
				
				
				    WHERE fc.ConfirmFnTime IS NOT NULL
				
				
				      AND fc.ProcessGrp IN (
				          'To Heat',
				          'Heat Finish',
				          'To Packing'
				      )
				),
				
				
				ProcessScope AS (
				
				
				    -- =========================================
				    -- ROUGH
				    -- =========================================
				
				    SELECT
				
				        'Rough'
				            AS ProcessGroup,
				
				        1
				            AS SortOrder,
				
				        b.AUFNR,
				
				        b.FinalQty,
				
				        'To Heat'
				            AS FinalConfirmProcess
				
				
				    FROM Base b
				
				
				    WHERE b.ProcessGrp2 = 'Rough'
				
				
				      AND (
				
				             b.ToHeat IS NULL
				
				          OR EXISTS (
				
				                 SELECT 1
				
				                 FROM Confirmed c
				
				                 WHERE c.AUNFR =
				                       b.AUFNR
				
				                   AND c.ProcessGrp =
				                       'To Heat'
				             )
				      )
				
				
				    UNION ALL
				
				
				    -- =========================================
				    --  HEAT
				    -- =========================================
				
				    SELECT
				
				        'Heat'
				            AS ProcessGroup,
				
				        2
				            AS SortOrder,
				
				        b.AUFNR,
				
				        b.FinalQty,
				
				        'Heat Finish'
				            AS FinalConfirmProcess
				
				
				    FROM Base b
				
				
				    WHERE b.ProcessGrp2 IN (
				        'Heat',
				        'Rough'
				    )
				
				
				      AND (
				
				             b.TimeFHeat IS NULL
				
				          OR EXISTS (
				
				                 SELECT 1
				
				                 FROM Confirmed c
				
				                 WHERE c.AUNFR =
				                       b.AUFNR
				
				                   AND c.ProcessGrp =
				                       'Heat Finish'
				             )
				      )
				
				
				    UNION ALL
				
				
				    -- =========================================
				    -- FINE
				    -- =========================================
				
				    SELECT
				
				        'Fine'
				            AS ProcessGroup,
				
				        3
				            AS SortOrder,
				
				        b.AUFNR,
				
				        b.FinalQty,
				
				        'To Packing'
				            AS FinalConfirmProcess
				
				
				    FROM Base b
				
				
				    WHERE (
				
				             b.ToPK IS NULL
				
				          OR EXISTS (
				
				                 SELECT 1
				
				                 FROM Confirmed c
				
				                 WHERE c.AUNFR =
				                       b.AUFNR
				
				                   AND c.ProcessGrp =
				                       'To Packing'
				             )
				      )
				),
				
				
				Summary AS (
				
				    SELECT
				
				        ps.ProcessGroup,
				
				        ps.SortOrder,
				
				
				        COUNT_BIG(*)
				            AS RequiredOrderCount,
				
				
				        SUM(
				
				            CAST(
				                ps.FinalQty
				                AS DECIMAL(18, 2)
				            )
				
				        ) AS RequiredTotalQty,
				
				
				        COUNT_BIG(
				            c.AUNFR
				        ) AS ConfirmedOrderCount,
				
				
				        SUM(
				
				            CASE
				
				                WHEN c.AUNFR IS NOT NULL
				
				                THEN CAST(
				                    ps.FinalQty
				                    AS DECIMAL(18, 2)
				                )
				
				                ELSE CAST(
				                    0
				                    AS DECIMAL(18, 2)
				                )
				
				            END
				
				        ) AS ConfirmedTotalQty
				
				
				    FROM ProcessScope ps
				
				
				    LEFT JOIN Confirmed c
				
				        ON c.AUNFR =
				           ps.AUFNR
				
				       AND c.ProcessGrp =
				           ps.FinalConfirmProcess
				
				
				    GROUP BY
				
				        ps.ProcessGroup,
				
				        ps.SortOrder
				)
				
				
				SELECT
				
				    ProcessGroup,
				
				    RequiredOrderCount,
				
				
				    ISNULL(
				        RequiredTotalQty,
				        0
				    ) AS RequiredTotalQty,
				
				
				    ConfirmedOrderCount,
				
				
				    ISNULL(
				        ConfirmedTotalQty,
				        0
				    ) AS ConfirmedTotalQty
				
				
				FROM Summary
				
				
				ORDER BY
				    SortOrder
				
				""";


		List<Object> params =
				new ArrayList<>();


		params.add(
				Timestamp.valueOf(
						expD.atStartOfDay()
				)
		);


		params.add(
				div
		);


		params.add(
				div
		);


		return jdbcTemplate.query(

				sql,

				(rs, rowNum) ->
						new FacConfirmProcessGroupDto(

								rs.getString(
										"ProcessGroup"
								),

								rs.getLong(
										"RequiredOrderCount"
								),

								rs.getBigDecimal(
										"RequiredTotalQty"
								),

								rs.getLong(
										"ConfirmedOrderCount"
								),

								rs.getBigDecimal(
										"ConfirmedTotalQty"
								)
						),

				params.toArray()
		);
	}
}