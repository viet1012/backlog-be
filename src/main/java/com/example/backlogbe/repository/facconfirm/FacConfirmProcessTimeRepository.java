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
				    AUNFR AS aufnr,
				    ProcessGrp AS processGrp,
				    ConfirmFnTime AS confirmFnTime,
				    Updater AS updater,
				    UpdatedAt AS updatedAt
				FROM F2Database.dbo.F2_Backlog_Fac_Confirm
				WHERE AUNFR IN (%s)
				""".formatted(placeholders);

		return jdbcTemplate.queryForList(
				sql,
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