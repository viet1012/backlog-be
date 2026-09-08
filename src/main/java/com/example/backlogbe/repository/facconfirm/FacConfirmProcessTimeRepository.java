package com.example.backlogbe.repository.facconfirm;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class FacConfirmProcessTimeRepository {

	private final JdbcTemplate jdbcTemplate;


	// =========================================================
	// FIND CONFIRMED PROCESSES
	// =========================================================

	public List<Map<String, Object>> findConfirmedProcesses(
			List<String> aufnrs
	) {

		if (aufnrs == null || aufnrs.isEmpty()) {
			return List.of();
		}

		List<String> cleanAufnrs = aufnrs.stream()
				.filter(value -> value != null && !value.isBlank())
				.map(String::trim)
				.distinct()
				.toList();

		if (cleanAufnrs.isEmpty()) {
			return List.of();
		}

		String placeholders = String.join(
				",",
				Collections.nCopies(
						cleanAufnrs.size(),
						"?"
				)
		);

		String sql = """
				SELECT
				    fc.AUNFR AS aufnr,
				    fc.ProcessGrp AS processGrp,
				    fc.ConfirmFnTime AS confirmFnTime,
				    fc.Updater AS updater,
				    fc.UpdatedAt AS updatedAt
				
				FROM F2Database.dbo.F2_Backlog_Fac_Confirm fc
				
				INNER JOIN F2Database.dbo.F2_Backlog_Main bl
				    ON bl.AUFNR = fc.AUNFR
				
				WHERE fc.AUNFR IN (%s)
				
				  AND fc.ConfirmFnTime IS NOT NULL
				
				  AND (
				         (
				             fc.ProcessGrp = 'To Drill'
				             AND bl.ToDrill IS NULL
				         )
				
				      OR (
				             fc.ProcessGrp = 'To Heat'
				             AND bl.ToHeat IS NULL
				         )
				
				      OR (
				             fc.ProcessGrp = 'Heat Start'
				             AND bl.TimeSQuenching IS NULL
				         )
				
				      OR (
				             fc.ProcessGrp = 'Heat Finish'
				             AND bl.TimeFHeat IS NULL
				         )
				
				      OR (
				             fc.ProcessGrp = 'To Packing'
				             AND bl.ToPK IS NULL
				         )
				  )
				
				""".formatted(placeholders);
		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> {
					Map<String, Object> row =
							new java.util.LinkedHashMap<>();

					row.put(
							"aufnr",
							rs.getString("aufnr")
					);

					row.put(
							"processGrp",
							rs.getString("processGrp")
					);

					Timestamp confirmFnTime =
							rs.getTimestamp("confirmFnTime");

					row.put(
							"confirmFnTime",
							confirmFnTime != null
									? confirmFnTime.toLocalDateTime()
									: null
					);

					row.put(
							"updater",
							rs.getString("updater")
					);

					Timestamp updatedAt =
							rs.getTimestamp("updatedAt");

					row.put(
							"updatedAt",
							updatedAt != null
									? updatedAt.toLocalDateTime()
									: null
					);

					return row;
				},
				cleanAufnrs.toArray()
		);
	}


	// =========================================================
	// UPSERT PROCESS TIME
	// =========================================================

	public int upsert(
			String aufnr,
			String processGrp,
			LocalDateTime confirmFnTime,
			String updater
	) {

		String sql = """
				IF EXISTS (
				    SELECT 1
				    FROM F2Database.dbo.F2_Backlog_Fac_Confirm
				    WHERE AUNFR = ?
				      AND ProcessGrp = ?
				)
				BEGIN
				
				    UPDATE F2Database.dbo.F2_Backlog_Fac_Confirm
				
				    SET
				        ConfirmFnTime = ?,
				        Updater = ?,
				        UpdatedAt = SYSDATETIME()
				
				    WHERE AUNFR = ?
				      AND ProcessGrp = ?
				
				END
				ELSE
				BEGIN
				
				    INSERT INTO F2Database.dbo.F2_Backlog_Fac_Confirm
				    (
				        AUNFR,
				        ProcessGrp,
				        ConfirmFnTime,
				        Updater,
				        UpdatedAt
				    )
				    VALUES
				    (
				        ?,
				        ?,
				        ?,
				        ?,
				        SYSDATETIME()
				    )
				
				END
				""";

		Timestamp time =
				Timestamp.valueOf(confirmFnTime);

		return jdbcTemplate.update(
				sql,

				// EXISTS
				aufnr,
				processGrp,

				// UPDATE
				time,
				updater,
				aufnr,
				processGrp,

				// INSERT
				aufnr,
				processGrp,
				time,
				updater
		);
	}
}