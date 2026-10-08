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
	// FIND EDIT STATES
	//
	// Theo từng AUFNR: cờ "không có Heat" (FacConfirmEditRules),
	// giá trị F2_Backlog_Main và bản ghi Fac Confirm mới nhất
	// (theo UpdatedAt, giống LatestConfirm của FacData) cho mỗi field.
	// =========================================================

	public Map<String, FacConfirmEditState> findEditStates(
			List<String> aufnrs
	) {

		if (aufnrs == null || aufnrs.isEmpty()) {
			return Map.of();
		}

		String placeholders = String.join(
				",",
				Collections.nCopies(
						aufnrs.size(),
						"?"
				)
		);

		List<String> fields =
				List.copyOf(
						FacConfirmEditRules.FIELD_TO_DB_PROCESS.keySet()
				);

		StringBuilder columns = new StringBuilder();

		for (String field : fields) {

			columns.append(
					"""
							    bl.[%s] AS [B_%s],
							    cp.[C_%s] AS [C_%s],
							""".formatted(
							FacConfirmEditRules.FIELD_TO_BACKLOG_COLUMN.get(field),
							field,
							field,
							field
					)
			);
		}

		StringBuilder pivot = new StringBuilder();

		for (int i = 0; i < fields.size(); i++) {

			String field = fields.get(i);

			pivot.append(
					"""
							        MAX(CASE WHEN x.ProcessGrp = '%s' THEN x.ConfirmFnTime END) AS [C_%s]%s
							""".formatted(
							FacConfirmEditRules.FIELD_TO_DB_PROCESS.get(field),
							field,
							i < fields.size() - 1 ? "," : ""
					)
			);
		}

		String sql = """
				SELECT
				"""
				+ columns
				+ """
				    bl.AUFNR,
				    CAST(
				        CASE
				            WHEN """
				+ FacConfirmEditRules.NO_HEAT_CONDITION
				+ """
				            THEN 1
				            ELSE 0
				        END
				        AS BIT
				    ) AS NoHeat
				
				FROM F2Database.dbo.F2_Backlog_Main bl
				
				OUTER APPLY (
				    SELECT
				"""
				+ pivot
				+ """
				    FROM (
				        SELECT
				            fc.ProcessGrp,
				            fc.ConfirmFnTime,
				            ROW_NUMBER() OVER (
				                PARTITION BY fc.ProcessGrp
				                ORDER BY fc.UpdatedAt DESC
				            ) AS rn
				        FROM F2Database.dbo.F2_Backlog_Fac_Confirm fc
				        WHERE fc.AUNFR = bl.AUFNR
				          AND fc.ConfirmFnTime IS NOT NULL
				    ) x
				    WHERE x.rn = 1
				) cp
				
				WHERE bl.AUFNR IN (%s)
				""".formatted(placeholders);

		Map<String, FacConfirmEditState> states =
				new java.util.LinkedHashMap<>();

		jdbcTemplate.query(
				sql,
				rs -> {

					Map<String, LocalDateTime> backlogValues =
							new java.util.LinkedHashMap<>();

					Map<String, LocalDateTime> confirmValues =
							new java.util.LinkedHashMap<>();

					for (String field : fields) {

						Timestamp backlog =
								rs.getTimestamp("B_" + field);

						if (backlog != null) {
							backlogValues.put(field, backlog.toLocalDateTime());
						}

						Timestamp confirm =
								rs.getTimestamp("C_" + field);

						if (confirm != null) {
							confirmValues.put(field, confirm.toLocalDateTime());
						}
					}

					String aufnr =
							rs.getString("AUFNR").trim();

					states.put(
							aufnr,
							new FacConfirmEditState(
									aufnr,
									rs.getBoolean("NoHeat"),
									Map.copyOf(backlogValues),
									Map.copyOf(confirmValues)
							)
					);
				},
				aufnrs.toArray()
		);

		return states;
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