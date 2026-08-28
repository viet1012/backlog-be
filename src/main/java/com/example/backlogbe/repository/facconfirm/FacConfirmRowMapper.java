package com.example.backlogbe.repository.facconfirm;

import com.example.backlogbe.dto.facconfirm.FacConfirmDto;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;


@Component
public class FacConfirmRowMapper
		implements RowMapper<FacConfirmDto> {


	@Override
	public FacConfirmDto mapRow(
			ResultSet rs,
			int rowNum
	) throws SQLException {

		return new FacConfirmDto(

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
		);
	}


	private LocalDateTime toLocalDateTime(
			Timestamp timestamp
	) {

		return timestamp == null
				? null
				: timestamp.toLocalDateTime();
	}
}