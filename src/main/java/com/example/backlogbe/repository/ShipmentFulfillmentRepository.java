package com.example.backlogbe.repository;


import com.example.backlogbe.dto.ShipmentFulfillmentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ShipmentFulfillmentRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<ShipmentFulfillmentDto> findByDateRange(
			LocalDate fromD,
			LocalDate toD
	) {

		String sql = """
				SELECT
				    ExportD,
				    COALESCE(RRONYU1, 'Stock') AS CusId,
				    ShipBy,
				
				    SUM(COALESCE(GAMNG, 0)) AS PoQty,
				    SUM(COALESCE(PkQty, 0)) AS FnQty,
				
				    CASE
				        WHEN SUM(COALESCE(GAMNG, 0)) = 0 THEN 0
				        ELSE
				            SUM(COALESCE(PkQty, 0)) * 1.0
				            / NULLIF(SUM(COALESCE(GAMNG, 0)), 0)
				    END AS FnRatio
				
				FROM F2Database.dbo.F2_Backlog_Main
				
				WHERE ExportD >= ?
				  AND ExportD < DATEADD(DAY, 1, ?)
				
				GROUP BY
				    ExportD,
				    RRONYU1,
				    ShipBy
				
				ORDER BY
				    ExportD,
				    RRONYU1,
				    ShipBy
				""";

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new ShipmentFulfillmentDto(
						rs.getTimestamp("ExportD") != null
								? rs.getTimestamp("ExportD").toLocalDateTime()
								: null,
						rs.getString("CusId"),
						rs.getString("ShipBy"),
						rs.getBigDecimal("PoQty"),
						rs.getBigDecimal("FnQty"),
						rs.getBigDecimal("FnRatio")
				),
				fromD,
				toD
		);
	}


}