package com.example.backlogbe.repository.backlog;

import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class BacklogMainRepository {

	private final JdbcTemplate jdbcTemplate;

	private final BacklogFilterSqlBuilder filterBuilder;

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


	public List<BacklogMainDto> findFiltered(
			int page,
			int size,
			BacklogFilterRequest request
	) {

		int offset =
				page * size;

		var queryParts =
				filterBuilder.build(
						request
				);

		String sql =
				SELECT_COLUMNS
						+ queryParts.where()
						+ """
						
						ORDER BY
						    UpdatedAt DESC,
						    VBELN DESC
						
						OFFSET ? ROWS
						FETCH NEXT ? ROWS ONLY
						""";

		List<Object> params =
				new ArrayList<>(
						queryParts.params()
				);

		params.add(offset);
		params.add(size);

		return jdbcTemplate.query(
				sql,
				rowMapper,
				params.toArray()
		);
	}


	public long countFiltered(
			BacklogFilterRequest request
	) {

		var queryParts =
				filterBuilder.build(
						request
				);

		String sql = """
				SELECT COUNT_BIG(*)
				FROM F2_Backlog_Main
				"""
				+ queryParts.where();

		Long total =
				jdbcTemplate.queryForObject(
						sql,
						Long.class,
						queryParts.params()
								.toArray()
				);

		return total == null
				? 0L
				: total;
	}
}