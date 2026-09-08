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
			
			    GROUP BY AUNFR
			),
			
			
			-- =====================================================
			-- PO CÓ CÔNG ĐOẠN HEAT
			-- =====================================================
			
			HeatProcess AS (
			
			    SELECT DISTINCT
			        r.AUFNR
			
			    FROM
			        [MANUFASPCPD].[dbo].[MANUFA_F_PD_DT_REQ_DTL1] r
			
			    WHERE
			        UPPER(
			            LTRIM(
			                RTRIM(
			                    ISNULL(
			                        r.AFVC_LTXA1,
			                        ''
			                    )
			                )
			            )
			        ) IN (
			            'INDUCTION H.T.',
			            'H.T.',
			            'H.T. (CARBURIZING)',
			            'H.T. (INDUCTION)',
			            'H.T. (OIL)',
			            'H.T. (VACCUME)',
			            'H.T.(CARBURIZING)',
			            'H.T.(OIL)',
			            'H.T.(OIL)/H.T. (VACCUME)',
			            'H.T. (F3)',
			            'HEAT',
			            'H.T.(CARBURIZING) (F3)'
			        )
			),
			
			
			-- =====================================================
			-- PO CÓ AGING (5 DAYS)
			-- =====================================================
			
			Aging5DaysProcess AS (
			
			    SELECT DISTINCT
			        r.AUFNR
			
			    FROM
			        [MANUFASPCPD].[dbo].[MANUFA_F_PD_DT_REQ_DTL1] r
			
			    WHERE
			        UPPER(
			            LTRIM(
			                RTRIM(
			                    ISNULL(
			                        r.AFVC_LTXA1,
			                        ''
			                    )
			                )
			            )
			        ) = 'AGING (5 DAYS)'
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
			
			        bl.Classify,
			
			        bl.ProcessGrp2,
			        bl.Div,
			
			
			        -- =================================================
			        -- HAS HEAT PROCESS
			        -- =================================================
			
			        CAST(
			            CASE
			                WHEN hp.AUFNR IS NOT NULL
			                THEN 1
			                ELSE 0
			            END
			            AS BIT
			        ) AS HasHeatProcess,
			
			
			        -- =================================================
			        -- IS DC53
			        --
			        -- DC53 = có Heat + Aging (5 days)
			        -- =================================================
			
			        CAST(
			            CASE
			                WHEN hp.AUFNR IS NOT NULL
			                 AND ag.AUFNR IS NOT NULL
			                THEN 1
			                ELSE 0
			            END
			            AS BIT
			        ) AS IsDC53,
			
					-- =================================================
					-- IS TD
					--
					-- TD = có Heat
					--      + PNAME bắt đầu HF_TD... hoặc TD...
					-- =================================================
					CAST(
					    CASE
					        WHEN hp.AUFNR IS NOT NULL
					         AND (
					                UPPER(
					                    LTRIM(
					                        RTRIM(
					                            ISNULL(
					                                bl.PNAME,
					                                ''
					                            )
					                        )
					                    )
					                ) LIKE 'HF[_]TD%'
					             OR UPPER(
					                    LTRIM(
					                        RTRIM(
					                            ISNULL(
					                                bl.PNAME,
					                                ''
					                            )
					                        )
					                    )
					                ) LIKE 'TD%'
					         )
					        THEN 1
					        ELSE 0
					    END
					    AS BIT
					) AS IsTD,
			        -- =================================================
			        -- NOTE
			        -- =================================================
			
					CASE
					    -- Không có Heat
					    WHEN hp.AUFNR IS NULL
					    THEN N'Không có Heat'
			
					    -- DC53 = Heat + Aging (5 days)
					    WHEN hp.AUFNR IS NOT NULL
					     AND ag.AUFNR IS NOT NULL
					    THEN N'DC53 - Chờ 5 ngày'
			
					    -- TD = Heat + PNAME bắt đầu HF_TD... hoặc TD...
					    WHEN hp.AUFNR IS NOT NULL
					     AND (
					            UPPER(
					                LTRIM(
					                    RTRIM(
					                        ISNULL(bl.PNAME, '')
					                    )
					                )
					            ) LIKE 'HF[_]TD%'
			
					         OR UPPER(
					                LTRIM(
					                    RTRIM(
					                        ISNULL(bl.PNAME, '')
					                    )
					                )
					            ) LIKE 'TD%'
					     )
					    THEN N'TD - Chờ 2 ngày'
			
					    ELSE NULL
					END AS Note,
			
			
			        COALESCE(
			            bl.ToDrill,
			            cp.Confirm_ToDrill
			        ) AS ToDrill,
			
			
			        COALESCE(
			            cp.Confirm_ToHeat,
			            bl.ToHeat
			        ) AS ToHeat,
			
			
			        COALESCE(
			            cp.Confirm_HeatStart,
			            bl.TimeSQuenching
			        ) AS Heat_Start,
			
			
			        COALESCE(
			            cp.Confirm_HeatFinish,
			            bl.TimeFHeat
			        ) AS Heat_Finish,
			
			
			        COALESCE(
			            cp.Confirm_ToPacking,
			            bl.ToPK
			        ) AS ToPK
			
			
			    FROM F2_Backlog_Main bl
			
			    LEFT JOIN ConfirmPivot cp
			        ON cp.AUNFR = bl.AUFNR
			
			    LEFT JOIN HeatProcess hp
			        ON hp.AUFNR = bl.AUFNR
			
			    LEFT JOIN Aging5DaysProcess ag
			        ON ag.AUFNR = bl.AUFNR
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
			
			    d.HasHeatProcess,
			
			    d.IsDC53,
			
				d.IsTD,
			
			    d.Note,
			
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
			String classify,
			String heatType,
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
		// =====================================================

		params.add(procGrp);
		params.add(procGrp);
		params.add(procGrp);


		// =====================================================
		// DIV
		// =====================================================

		params.add(div);
		params.add(div);


		StringBuilder where =
				new StringBuilder(
						"""
								
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
								
								"""
				);


		// =====================================================
		// CLASSIFY
		//
		// UI input:
		//
		// Sale  -> DB Classify = Sale
		// Stock -> DB Classify <> Sale
		// =====================================================

		if (
				classify != null
						&& !classify.isBlank()
		) {

			// =================================================
			// SALE
			// =================================================

			if (
					"Sale".equalsIgnoreCase(
							classify
					)
			) {

				where.append(
						"""
								
								AND LTRIM(
									  RTRIM(
											ISNULL(
												  d.Classify,
												  ''
											)
									  )
								) = 'Sale'
								
								"""
				);
			}


			// =================================================
			// STOCK
			// =================================================

			else if (
					"Stock".equalsIgnoreCase(
							classify
					)
			) {

				where.append(
						"""
								
								AND LTRIM(
									  RTRIM(
											ISNULL(
												  d.Classify,
												  ''
											)
									  )
								) <> 'Sale'
								
								"""
				);
			}
		}
		// =====================================================
		// HEAT TYPE
		// =====================================================

		if (
				heatType != null
						&& !"All".equalsIgnoreCase(heatType)
		) {

			if ("Normal".equalsIgnoreCase(heatType)) {

				where.append(
						"""
								
								AND d.IsDC53 = 0
								AND d.IsTD = 0
								
								"""
				);

			} else if ("DC53".equalsIgnoreCase(heatType)) {

				where.append(
						"""
								
								AND d.HasHeatProcess = 1
								AND d.IsDC53 = 1
								
								"""
				);

			} else if ("TD".equalsIgnoreCase(heatType)) {

				where.append(
						"""
								
								AND d.HasHeatProcess = 1
								AND d.IsTD = 1
								AND d.IsDC53 = 0
								
								"""
				);
			}
		}

		return where.toString();
	}


	// =========================================================
	// FIND PAGE
	// =========================================================

	public List<FacConfirmDto> findPage(
			String div,
			LocalDate expD,
			String procGrp,
			String classify,
			String heatType,
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
						classify,
						heatType,
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

	public long count(
			String div,
			LocalDate expD,
			String procGrp,
			String classify,
			String heatType
	) {

		List<Object> params =
				new ArrayList<>();

		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						classify,
						heatType,
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
			String classify,
			String heatType,
			int page,
			int size,
			List<FacConfirmFilterItem> filters,
			String logicOperator
	) {

		int offset = page * size;

		List<Object> params = new ArrayList<>();

		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						classify,
						heatType,
						params
				);

		FacConfirmFilterSqlBuilder.QueryParts filterParts =
				filterBuilder.build(
						filters,
						logicOperator
				);

		params.addAll(filterParts.params());

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

		params.add(offset);
		params.add(size);

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
			String classify,
			String heatType,
			List<FacConfirmFilterItem> filters,
			String logicOperator
	) {

		List<Object> params = new ArrayList<>();

		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						classify,
						heatType,
						params
				);

		FacConfirmFilterSqlBuilder.QueryParts filterParts =
				filterBuilder.build(
						filters,
						logicOperator
				);

		params.addAll(filterParts.params());

		String sql =
				FAC_DATA_CTE
						+ """
						
						SELECT COUNT_BIG(*)
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

		return total == null ? 0L : total;
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
			String classify,
			String heatType,
			List<FacConfirmFilterItem> filters
	) {

		FacConfirmColumnMetadataProvider.ColumnMeta meta =
				metadataProvider.get(field);

		String column =
				"d.[" + meta.name() + "]";

		boolean dateField =
				meta.type()
						== FacConfirmColumnMetadataProvider.ColumnType.DATE;

		String valueExpression;

		if (dateField) {

			valueExpression =
					"""
							CASE
								WHEN %s IS NULL THEN ''
								ELSE CONVERT(VARCHAR(10), %s, 23)
							END
							""".formatted(column, column);

		} else {

			valueExpression =
					"""
							ISNULL(
								CAST(%s AS NVARCHAR(500)),
								''
							)
							""".formatted(column);
		}

		List<FacConfirmFilterItem> otherFilters =
				filters == null
						? List.of()
						: filters.stream()
						.filter(item -> item != null)
						.filter(item ->
								item.field() == null
										|| !field.equalsIgnoreCase(item.field())
						)
						.toList();

		List<Object> params = new ArrayList<>();

		String baseWhere =
				buildBaseWhere(
						div,
						expD,
						procGrp,
						classify,
						heatType,
						params
				);

		FacConfirmFilterSqlBuilder.QueryParts filterParts =
				filterBuilder.build(
						otherFilters,
						"and"
				);

		params.addAll(filterParts.params());

		StringBuilder sql = new StringBuilder();

		sql.append(FAC_DATA_CTE);

		sql.append(
				"""
						
						SELECT DISTINCT
							%s AS FilterValue
						FROM FacData d
						
						""".formatted(valueExpression)
		);

		sql.append(baseWhere);
		sql.append(filterParts.sql());

		String safeSearch =
				search == null
						? ""
						: search.trim();

		if (!safeSearch.isBlank()) {

			sql.append(
					" AND " + valueExpression + " LIKE ? "
			);

			params.add("%" + safeSearch + "%");
		}

		sql.append(
				"""
						
						ORDER BY FilterValue
						
						"""
		);

		return jdbcTemplate.query(
				sql.toString(),
				(rs, rowNum) -> rs.getString("FilterValue"),
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
		 * Business:
		 *
		 * Rough:
		 *   final confirm = To Heat
		 *   Required khi Backlog_Main.ToHeat IS NULL
		 *
		 * Heat:
		 *   final confirm = Heat Finish
		 *   Required khi Backlog_Main.TimeFHeat IS NULL
		 *
		 * Fine:
		 *   final confirm = To Packing
		 *   Required khi Backlog_Main.ToPK IS NULL
		 *
		 * F2_Backlog_Main LUÔN ưu tiên.
		 *
		 * Nếu Main đã có final time:
		 *   -> không thuộc Fac Confirm scope
		 *   -> không tính Required
		 *   -> không tính Confirmed
		 *
		 * Nếu Main final time NULL:
		 *   -> thuộc Required
		 *
		 * Trong Required:
		 *   có Fac Confirm record tương ứng
		 *   -> Confirmed
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
				    -- Main.ToHeat NULL mới thuộc scope
				    -- =========================================
				
				    SELECT
				        'Rough' AS ProcessGroup,
				        1 AS SortOrder,
				
				        b.AUFNR,
				        b.FinalQty,
				
				        'To Heat' AS FinalConfirmProcess
				
				    FROM Base b
				
				    WHERE b.ProcessGrp2 = 'Rough'
				
				      AND b.ToHeat IS NULL
				
				
				    UNION ALL
				
				
				    -- =========================================
				    -- HEAT
				    -- Main.TimeFHeat NULL mới thuộc scope
				    -- =========================================
				
				    SELECT
				        'Heat' AS ProcessGroup,
				        2 AS SortOrder,
				
				        b.AUFNR,
				        b.FinalQty,
				
				        'Heat Finish' AS FinalConfirmProcess
				
				    FROM Base b
				
				    WHERE b.ProcessGrp2 IN (
				        'Heat',
				        'Rough'
				    )
				
				      AND b.TimeFHeat IS NULL
				
				
				    UNION ALL
				
				
				    -- =========================================
				    -- FINE
				    -- Main.ToPK NULL mới thuộc scope
				    -- =========================================
				
				    SELECT
				        'Fine' AS ProcessGroup,
				        3 AS SortOrder,
				
				        b.AUFNR,
				        b.FinalQty,
				
				        'To Packing' AS FinalConfirmProcess
				
				    FROM Base b
				
				    WHERE b.ToPK IS NULL
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
				        ON c.AUNFR = ps.AUFNR
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