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
				.map(value -> value.trim())
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
				    fc.UpdatedAt AS updatedAt,
				
				    CAST(
				        CASE
				            WHEN """
				+ FacConfirmEditRules.NO_HEAT_CONDITION
				+ """
				            THEN 1
				            ELSE 0
				        END
				        AS BIT
				    ) AS noHeat
				
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

					// Công đoạn sở hữu ô (rough-no-heat: To CLG thuộc Rough)
					row.put(
							"ownerProcess",
							FacConfirmEditRules.getOwnerProcess(
									new FacConfirmEditRules.EditRow(
											rs.getString("aufnr"),
											rs.getBoolean("noHeat")
									),
									FacConfirmEditRules.fieldOfDbProcess(
											rs.getString("processGrp")
									)
							)
					);

					return row;
				},
				cleanAufnrs.toArray()
		);
	}


	// =========================================================
	// FIND "KHÔNG CÓ HEAT" AUFNR (FacConfirmEditRules)
	// =========================================================

	public List<String> findNoHeatNoteAufnrs(
			List<String> aufnrs
	) {

		if (aufnrs == null || aufnrs.isEmpty()) {
			return List.of();
		}

		String placeholders = String.join(
				",",
				Collections.nCopies(
						aufnrs.size(),
						"?"
				)
		);

		String sql = """
				SELECT DISTINCT
				    bl.AUFNR

				FROM F2Database.dbo.F2_Backlog_Main bl

				WHERE bl.AUFNR IN (%s)

				  AND """.formatted(placeholders)
				+ FacConfirmEditRules.NO_HEAT_CONDITION;

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> rs.getString("AUFNR"),
				aufnrs.toArray()
		);
	}


	// =========================================================
	// UPSERT PROCESS TIME
	//
	// MERGE ... WITH (HOLDLOCK): khóa range theo (AUNFR, ProcessGrp)
	// trong transaction, nên 2 request lưu cùng lúc không tạo bản ghi trùng.
	// Unique index: sql/fac_confirm_unique_index.sql (chạy sau khi dọn trùng).
	// =========================================================

	public int upsert(
			String aufnr,
			String processGrp,
			LocalDateTime confirmFnTime,
			String updater
	) {

		String sql = """
				MERGE F2Database.dbo.F2_Backlog_Fac_Confirm WITH (HOLDLOCK) AS t
				
				USING (
				    SELECT
				        ? AS AUNFR,
				        ? AS ProcessGrp
				) AS s
				
				    ON t.AUNFR = s.AUNFR
				   AND t.ProcessGrp = s.ProcessGrp
				
				WHEN MATCHED THEN
				    UPDATE SET
				        ConfirmFnTime = ?,
				        Updater = ?,
				        UpdatedAt = SYSDATETIME()
				
				WHEN NOT MATCHED THEN
				    INSERT
				    (
				        AUNFR,
				        ProcessGrp,
				        ConfirmFnTime,
				        Updater,
				        UpdatedAt
				    )
				    VALUES
				    (
				        s.AUNFR,
				        s.ProcessGrp,
				        ?,
				        ?,
				        SYSDATETIME()
				    );
				""";

		Timestamp time =
				Timestamp.valueOf(confirmFnTime);

		return jdbcTemplate.update(
				sql,

				// USING
				aufnr,
				processGrp,

				// UPDATE
				time,
				updater,

				// INSERT
				time,
				updater
		);
	}
}