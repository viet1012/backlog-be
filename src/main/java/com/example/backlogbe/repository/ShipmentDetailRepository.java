package com.example.backlogbe.repository;


import com.example.backlogbe.dto.ShipmentDetailFilter;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import com.example.backlogbe.repository.backlog.BacklogRowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ShipmentDetailRepository {

	private final JdbcTemplate jdbcTemplate;
	private final BacklogRowMapper rowMapper;

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

	public List<BacklogMainDto> findDetail(
			ShipmentDetailFilter filter
	) {

		StringBuilder where =
				new StringBuilder(
						" WHERE 1 = 1 "
				);

		List<Object> params =
				new ArrayList<>();


		// CUSTOMER
		if (hasText(filter.cusId())) {

			String cusId =
					filter.cusId().trim();

			if ("STOCK".equalsIgnoreCase(cusId)) {

				where.append(
						" AND RRONYU1 IS NULL "
				);

			} else {

				where.append(
						" AND RRONYU1 = ? "
				);

				params.add(cusId);
			}
		}


		// SHIP BY
		if (hasText(filter.shipBy())) {

			String shipBy =
					filter.shipBy().trim();

			if ("N/A".equalsIgnoreCase(shipBy)) {

				where.append(
						" AND ShipBy IS NULL "
				);

			} else if (
					"EXP".equalsIgnoreCase(shipBy)
							|| "EXPRESS".equalsIgnoreCase(shipBy)
			) {

				where.append("""
						
						AND UPPER(
						    LTRIM(
						        RTRIM(ShipBy)
						    )
						)
						IN (
						    'EXP',
						    'EXPRESS'
						)
						""");

			} else {

				where.append("""
						
						AND UPPER(
						    LTRIM(
						        RTRIM(ShipBy)
						    )
						) = ?
						""");

				params.add(
						shipBy.toUpperCase()
				);
			}
		}


		// EXPORT DATE
		if (filter.exportDate() != null) {

			LocalDateTime from =
					filter.exportDate()
							.atStartOfDay();

			LocalDateTime to =
					filter.exportDate()
							.plusDays(1)
							.atStartOfDay();

			where.append("""
					
					AND ExportD >= ?
					AND ExportD < ?
					""");

			params.add(
					Timestamp.valueOf(from)
			);

			params.add(
					Timestamp.valueOf(to)
			);
		}


		String sql =
				SELECT_COLUMNS
						+ where
						+ """
						
						ORDER BY
						    ExportD,
						    RRONYU1,
						    ShipBy,
						    VBELN,
						    AUFNR
						""";


		return jdbcTemplate.query(
				sql,
				rowMapper,
				params.toArray()
		);
	}


	private boolean hasText(
			String value
	) {
		return value != null
				&& !value.isBlank();
	}
}
