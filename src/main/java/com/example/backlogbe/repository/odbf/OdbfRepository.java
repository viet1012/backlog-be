package com.example.backlogbe.repository.odbf;

import com.example.backlogbe.dto.odbf.OdbfSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class OdbfRepository {

	private final JdbcTemplate jdbcTemplate;


	// =========================================================
	// SUMMARY
	// =========================================================

	public List<OdbfSummaryDto> findSummary() {

		String sql = """
				SELECT
				    bl.ProductGrp,
				    bl.Status2,
				    bl.ExportD,
				    COUNT(bl.AUFNR) AS CountPO,
				    COALESCE(
				        SUM(
				            CAST(
				                COALESCE(bl.FinalQty, 0)
				                AS DECIMAL(38, 4)
				            )
				        ),
				        0
				    ) AS SumQty
				
				FROM F2_Backlog_Main bl
				
				WHERE bl.DIV = 'PR'
				
				  AND bl.ExportD BETWEEN
				      DATEADD(DAY, -2, GETDATE())
				      AND DATEADD(DAY, 7, GETDATE())
				
				GROUP BY
				    bl.ProductGrp,
				    bl.Status2,
				    bl.ExportD
				
				ORDER BY
				    bl.ProductGrp,
				    bl.Status2,
				    bl.ExportD
				""";


		return jdbcTemplate.query(
				sql,

				(rs, rowNum) ->
						new OdbfSummaryDto(
								rs.getString(
										"ProductGrp"
								),

								rs.getString(
										"Status2"
								),

								rs.getTimestamp(
										"ExportD"
								) == null
										? null
										: rs.getTimestamp(
										"ExportD"
								).toLocalDateTime(),

								rs.getLong(
										"CountPO"
								),

								rs.getBigDecimal(
										"SumQty"
								)
						)
		);
	}
}