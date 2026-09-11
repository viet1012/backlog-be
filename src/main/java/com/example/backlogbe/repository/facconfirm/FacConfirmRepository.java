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
			
			        bl.WaitingDays,
			        bl.Heat_Note AS RawHeatNote,
			
			        -- =================================================
			        -- PO KHÔNG CẦN FAC CONFIRM
			        -- =================================================
			        CAST(
			            CASE
			                WHEN (
			                       bl.ProductGrp = 'Cam'
			                    OR bl.FERTH LIKE 'Backing Plug%'
			                    OR bl.PHCD LIKE 'J%'
			                    OR bl.PRT_ADDCMT2 LIKE '%Xuat kho%'
			                    OR bl.ProcessGrp2 = 'MTO'
			                    OR (
			                           bl.ZGLOBAL_CODE IS NULL
			                       AND bl.PRT_ADDCMT2 NOT LIKE '%FNK%'
			                    )
			                    OR bl.Status2 <> 'ON PROGRESS'
			                )
			                THEN 1
			                ELSE 0
			            END
			            AS BIT
			        ) AS IsNoCount,
			
			        -- =================================================
			        -- HAS HEAT PROCESS
			        -- =================================================
			        CAST(
			            CASE
			                WHEN UPPER(
			                    LTRIM(
			                        RTRIM(
			                            ISNULL(bl.Heat_Note, '')
			                        )
			                    )
			                ) = 'NO HEAT'
			                THEN 0
			
			                ELSE 1
			            END
			            AS BIT
			        ) AS HasHeatProcess,
			
			        -- =================================================
			        -- IS DC53
			        -- WaitingDays = 5
			        -- =================================================
			        CAST(
			            CASE
			                WHEN bl.WaitingDays = 5
			                THEN 1
			                ELSE 0
			            END
			            AS BIT
			        ) AS IsDC53,
			
			        -- =================================================
			        -- IS TD
			        -- WaitingDays = 2
			        -- =================================================
			        CAST(
			            CASE
			                WHEN bl.WaitingDays = 2
			                THEN 1
			                ELSE 0
			            END
			            AS BIT
			        ) AS IsTD,
			
			        -- =================================================
			        -- IS MOLYPDEN
			        -- WaitingDays = 7
			        -- =================================================
			        CAST(
			            CASE
			                WHEN bl.WaitingDays = 7
			                THEN 1
			                ELSE 0
			            END
			            AS BIT
			        ) AS IsMolypden,
			
			        -- =================================================
			        -- HEAT NOTE
			        -- Theo đúng query nghiệp vụ
			        -- =================================================
			        CASE
			            WHEN bl.WaitingDays = 5
			            THEN N'DC53 chờ 5 ngày'
			
			            WHEN bl.WaitingDays = 2
			            THEN N'TD chờ 2 ngày'
			
			            WHEN bl.WaitingDays = 7
			            THEN N'Molypden chờ 7 ngày'
			
			            WHEN UPPER(
			                LTRIM(
			                    RTRIM(
			                        ISNULL(bl.Heat_Note, '')
			                    )
			                )
			            ) = 'NO HEAT'
			            THEN N'Không có Heat'
			
			            ELSE N''
			        END AS Note,
			
			        -- =================================================
			        -- PROCESS DATETIME
			        -- Main ưu tiên, FacConfirm fallback
			        -- =================================================
			        COALESCE(
			            bl.ToDrill,
			            cp.Confirm_ToDrill
			        ) AS ToDrill,
			
			        COALESCE(
			            bl.ToHeat,
			            cp.Confirm_ToHeat
			        ) AS ToHeat,
			
			        COALESCE(
			            bl.TimeSQuenching,
			            cp.Confirm_HeatStart
			        ) AS Heat_Start,
			
			        COALESCE(
			            bl.TimeFHeat,
			            cp.Confirm_HeatFinish
			        ) AS Heat_Finish,
			
			        COALESCE(
			            bl.ToPK,
			            cp.Confirm_ToPacking
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
			
			    d.WaitingDays,
			
			    d.HasHeatProcess,
			    d.IsDC53,
			    d.IsTD,
			    d.IsMolypden,
			
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
											AND (
												   d.ProcessGrp2 IN (
													   'Fine',
													   'Heat',
													   'Rough'
												   )
												OR d.ProcessGrp2 IS NULL
											)
									   )
								
									   OR
								
									   (
											? = 'Heat'
											AND (
												   d.ProcessGrp2 IN (
													   'Heat',
													   'Rough'
												   )
												OR d.ProcessGrp2 IS NULL
											)
									   )
								
									   OR
								
									   (
											? = 'Rough'
											AND (
												   d.ProcessGrp2 = 'Rough'
												OR d.ProcessGrp2 IS NULL
											)
									   )
								  )
								
								  AND (
										 d.Div = ?
								
									  OR (
										   ? = 'GU'
										   AND d.Div LIKE '%G'
									  )
								  )
								
								  AND d.IsNoCount = 0
								
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
									 AND d.IsMolypden = 0
								
								"""
				);

			} else if ("DC53".equalsIgnoreCase(heatType)) {

				where.append(
						"""
								
									 AND d.IsDC53 = 1
								
								"""
				);

			} else if ("TD".equalsIgnoreCase(heatType)) {

				where.append(
						"""
								
									 AND d.IsTD = 1
								
								"""
				);

			} else if ("Molypden".equalsIgnoreCase(heatType)) {

				where.append(
						"""
								
									 AND d.IsMolypden = 1
								
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
			LocalDate expD,
			String classify,
			String heatType,
			List<FacConfirmFilterItem> filters,
			String logicOperator
	) {

		List<Object> params =
				new ArrayList<>();


		// =========================================================
		// BASE PARAMS
		// =========================================================
		params.add(
				Timestamp.valueOf(
						expD.atStartOfDay()
				)
		);

		params.add(div);
		params.add(div);


		// =========================================================
		// BASE WHERE
		// Không có procGrp ở đây vì cần tính cả 3 nút cùng lúc
		// =========================================================

		StringBuilder where =
				new StringBuilder(
						"""
								
								WHERE d.ExportD <= ?
								
								  AND (
										 d.Div = ?
									  OR (
										   ? = 'GU'
										   AND d.Div LIKE '%G'
									  )
								  )
								
								  AND d.IsNoCount = 0
								
								"""
				);


		// =========================================================
		// CLASSIFY
		// =========================================================

		if (
				classify != null
						&& !classify.isBlank()
		) {

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

			} else if (
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


		// =========================================================
		// HEAT TYPE
		// =========================================================

		if (
				heatType != null
						&& !"All".equalsIgnoreCase(
						heatType
				)
		) {

			if (
					"Normal".equalsIgnoreCase(
							heatType
					)
			) {

				where.append(
						"""
								
								AND d.IsDC53 = 0
								AND d.IsTD = 0
								AND d.IsMolypden = 0
								
								"""
				);

			} else if (
					"DC53".equalsIgnoreCase(
							heatType
					)
			) {

				where.append(
						"""
								
								AND d.IsDC53 = 1
								
								"""
				);

			} else if (
					"TD".equalsIgnoreCase(
							heatType
					)
			) {

				where.append(
						"""
								
								AND d.IsTD = 1
								
								"""
				);

			} else if (
					"Molypden".equalsIgnoreCase(
							heatType
					)
			) {

				where.append(
						"""
								
								AND d.IsMolypden = 1
								
								"""
				);
			}
		}


		// =========================================================
		// EXCEL FILTER
		// =========================================================

		FacConfirmFilterSqlBuilder.QueryParts filterParts =
				filterBuilder.build(
						filters,
						logicOperator
				);

		where.append(
				filterParts.sql()
		);

		params.addAll(
				filterParts.params()
		);


		// =========================================================
		// SQL
		// =========================================================

		String sql =
				FAC_DATA_CTE
						+ """
						
						,
						FilteredBase AS (
						
						    SELECT
						        d.*
						
						    FROM FacData d
						
						"""
						+ where
						+ """
						
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
						        'Rough' AS ProcessGroup,
						        1 AS SortOrder,
						        b.AUFNR,
						        b.FinalQty,
						        'To Heat' AS FinalConfirmProcess
						
						    FROM FilteredBase b
						
						    WHERE (
						           b.ProcessGrp2 = 'Rough'
						           OR b.ProcessGrp2 IS NULL
						    )
						
						
						    UNION ALL
						
						
						    -- =========================================
						    -- HEAT
						    -- =========================================
						    SELECT
						        'Heat' AS ProcessGroup,
						        2 AS SortOrder,
						        b.AUFNR,
						        b.FinalQty,
						        'Heat Finish' AS FinalConfirmProcess
						
						    FROM FilteredBase b
						
						    WHERE (
						           b.ProcessGrp2 IN (
						               'Heat',
						               'Rough'
						           )
						           OR b.ProcessGrp2 IS NULL
						    )
						
						
						    UNION ALL
						
						
						    -- =========================================
						    -- FINE
						    -- =========================================
						    SELECT
						        'Fine' AS ProcessGroup,
						        3 AS SortOrder,
						        b.AUFNR,
						        b.FinalQty,
						        'To Packing' AS FinalConfirmProcess
						
						    FROM FilteredBase b
						
						    WHERE (
						           b.ProcessGrp2 IN (
						               'Fine',
						               'Heat',
						               'Rough'
						           )
						           OR b.ProcessGrp2 IS NULL
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
						
						ORDER BY
						    SortOrder
						
						""";


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