package com.example.backlogbe.repository.facconfirm;

import com.example.backlogbe.dto.facconfirm.FacConfirmDto;
import com.example.backlogbe.dto.facconfirm.FacConfirmProcessGroupDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class FacConfirmRepository {

	private static final String BASE_FROM_WHERE = """
			FROM F2_Backlog_Main bl
			WHERE bl.ExportD <= :expD
			  AND (
			         (:procGrp = 'Fine'
			             AND bl.ProcessGrp2 IN ('Fine', 'Heat', 'Rough'))
			
			      OR (:procGrp = 'Heat'
			             AND bl.ProcessGrp2 IN ('Heat', 'Rough'))
			
			      OR (:procGrp = 'Rough'
			             AND bl.ProcessGrp2 = 'Rough')
			  )
			  AND (
			         bl.Div = :div
			
			      OR (
			             :div = 'GU'
			             AND bl.Div LIKE '%G'
			      )
			  )
			""";


	// =========================================================
	// BASE WHERE FOR DETAIL
	// =========================================================
	private final NamedParameterJdbcTemplate jdbcTemplate;


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

		int offset = page * size;

		String sql = """
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
				"""
				+ BASE_FROM_WHERE
				+ """
				
				ORDER BY
				    bl.ExportD,
				    bl.ProductGrp,
				    bl.AUFNR
				
				OFFSET :offset ROWS
				FETCH NEXT :size ROWS ONLY
				""";

		MapSqlParameterSource params =
				buildDetailParams(
						div,
						expD,
						procGrp
				)
						.addValue(
								"offset",
								offset
						)
						.addValue(
								"size",
								size
						);

		return jdbcTemplate.query(
				sql,
				params,
				(rs, rowNum) ->
						new FacConfirmDto(

								rs.getString(
										"FERTH"
								),

								rs.getString(
										"ProductGrp"
								),

								rs.getString(
										"AUFNR"
								),

								rs.getString(
										"ZGLOBAL_CODE"
								),

								rs.getString(
										"PNAME"
								),

								toLocalDateTime(
										rs.getTimestamp(
												"IssueD"
										)
								),

								toLocalDateTime(
										rs.getTimestamp(
												"ExportD"
										)
								),

								rs.getString(
										"CusId"
								),

								rs.getString(
										"ShipBy"
								),

								rs.getString(
										"MTO_ID"
								),

								rs.getString(
										"PRT_ADDCMT2"
								),

								rs.getString(
										"CurrentProcess"
								),

								rs.getBigDecimal(
										"FinalQty"
								),

								toLocalDateTime(
										rs.getTimestamp(
												"ToDrill"
										)
								),

								toLocalDateTime(
										rs.getTimestamp(
												"ToHeat"
										)
								),

								toLocalDateTime(
										rs.getTimestamp(
												"Heat_Start"
										)
								),

								toLocalDateTime(
										rs.getTimestamp(
												"Heat_Finish"
										)
								),

								toLocalDateTime(
										rs.getTimestamp(
												"ToPK"
										)
								)
						)
		);
	}


	// =========================================================
	// COUNT DETAIL
	// =========================================================

	public long count(
			String div,
			LocalDate expD,
			String procGrp
	) {

		String sql = """
				SELECT COUNT_BIG(*)
				"""
				+ BASE_FROM_WHERE;

		Long total =
				jdbcTemplate.queryForObject(
						sql,
						buildDetailParams(
								div,
								expD,
								procGrp
						),
						Long.class
				);

		return total == null
				? 0L
				: total;
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
		 *   ProcessGrp2 = 'Rough'
		 *
		 * Heat:
		 *   ProcessGrp2 IN ('Heat', 'Rough')
		 *
		 * Fine:
		 *   ProcessGrp2 IN ('Fine', 'Heat', 'Rough')
		 *
		 * API display order:
		 *
		 * Rough -> Heat -> Fine
		 */

		String sql = """
				WITH Base AS (
				    SELECT
				        bl.ProcessGrp2,
				        ISNULL(bl.FinalQty, 0) AS FinalQty
				
				    FROM F2_Backlog_Main bl
				
				    WHERE bl.ExportD <= :expD
				
				      AND (
				             bl.Div = :div
				
				          OR (
				                 :div = 'GU'
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
				                FinalQty AS DECIMAL(18, 2)
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
				                FinalQty AS DECIMAL(18, 2)
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
				                FinalQty AS DECIMAL(18, 2)
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


		MapSqlParameterSource params =
				buildCommonParams(
						div,
						expD
				);


		return jdbcTemplate.query(
				sql,
				params,
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
						)
		);
	}
	// =========================================================
	// DETAIL PARAMS
	// =========================================================

	private MapSqlParameterSource buildDetailParams(
			String div,
			LocalDate expD,
			String procGrp
	) {

		return buildCommonParams(
				div,
				expD
		)
				.addValue(
						"procGrp",
						procGrp
				);
	}


	// =========================================================
	// COMMON PARAMS
	// =========================================================

	private MapSqlParameterSource buildCommonParams(
			String div,
			LocalDate expD
	) {

		/*
		 * LocalDate:
		 *
		 * 2026-08-27
		 *
		 * =>
		 *
		 * 2026-08-27 00:00:00
		 */
		Timestamp exportDate =
				Timestamp.valueOf(
						expD.atStartOfDay()
				);

		return new MapSqlParameterSource()
				.addValue(
						"div",
						div
				)
				.addValue(
						"expD",
						exportDate
				);
	}


	// =========================================================
	// DATE CONVERTER
	// =========================================================

	private LocalDateTime toLocalDateTime(
			Timestamp timestamp
	) {

		return timestamp == null
				? null
				: timestamp.toLocalDateTime();
	}
}