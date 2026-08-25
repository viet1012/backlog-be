package com.example.backlogbe.repository.backlog;


import com.example.backlogbe.dto.backlog.BacklogMainDto;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@Component
public class BacklogRowMapper
		implements RowMapper<BacklogMainDto> {

	@Override
	public BacklogMainDto mapRow(
			ResultSet rs,
			int rowNum
	) throws SQLException {

		return new BacklogMainDto(
				rs.getString("VBELN"),
				rs.getString("ZGLOBAL_CODE"),
				rs.getString("PIER_AUFNR"),
				rs.getString("AUFNR"),

				toLocalDateTime(
						rs.getTimestamp("IssueD")
				),

				toLocalDateTime(
						rs.getTimestamp("ProductionD")
				),

				toLocalDateTime(
						rs.getTimestamp("PromiseD")
				),

				toLocalDateTime(
						rs.getTimestamp("ExportD")
				),

				toLocalDateTime(
						rs.getTimestamp("ORG_Date")
				),

				toLocalDateTime(
						rs.getTimestamp("MSM_Ship")
				),

				rs.getString("PNAME"),
				rs.getString("RRONYU1"),
				rs.getString("ShipBy"),

				rs.getBigDecimal("GAMNG"),
				rs.getBigDecimal("NETPR"),

				rs.getString("PHCD"),

				rs.getBigDecimal("KWMENG"),

				rs.getString("RODENK"),
				rs.getString("LOEKZ"),
				rs.getString("MTO_ID"),

				rs.getString("PRT_ADDCMT1"),
				rs.getString("PRT_ADDCMT2"),

				getNullableInteger(
						rs,
						"PRT_STS"
				),

				rs.getString("Div"),
				rs.getString("FERTH"),
				rs.getString("PO_SRG_Convert"),

				toLocalDateTime(
						rs.getTimestamp("ToDrill")
				),

				toLocalDateTime(
						rs.getTimestamp("ToHeat")
				),

				toLocalDateTime(
						rs.getTimestamp("ToPK")
				),

				rs.getString("Status"),
				rs.getString("CurrentProcess"),
				rs.getString("HeatCharge"),

				rs.getBigDecimal("ProcessQty"),
				rs.getBigDecimal("Z300Qty"),
				rs.getBigDecimal("PkQty"),
				rs.getBigDecimal("FinalQty"),

				toLocalDateTime(
						rs.getTimestamp("TimeSQuenching")
				),

				toLocalDateTime(
						rs.getTimestamp("TimeFHeat")
				),

				rs.getString("C_PRODH"),
				rs.getString("C_KEYCONTROL1"),
				rs.getString("C_KEYCONTROL3"),

				rs.getString("Updater"),

				toLocalDateTime(
						rs.getTimestamp("UpdatedAt")
				)
		);
	}


	private Integer getNullableInteger(
			ResultSet rs,
			String column
	) throws SQLException {

		int value =
				rs.getInt(column);

		return rs.wasNull()
				? null
				: value;
	}


	private LocalDateTime toLocalDateTime(
			Timestamp timestamp
	) {

		return timestamp == null
				? null
				: timestamp.toLocalDateTime();
	}
}