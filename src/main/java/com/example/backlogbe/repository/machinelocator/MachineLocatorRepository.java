package com.example.backlogbe.repository.machinelocator;

import com.example.backlogbe.model.MachineLocatorFloorRow;
import com.example.backlogbe.model.MachineLocatorMachineRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MachineLocatorRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;


	private static final String FLOOR_COLUMNS = """
			floor_id,
			name,
			drawing_title,
			image_url,
			crop_json,
			page_w,
			page_h,
			ppp,
			export_meta_json,
			COALESCE(version, 0) AS version,
			updated_at,
			updated_by
			""";


	private static final RowMapper<MachineLocatorFloorRow> FLOOR_ROW_MAPPER =
			(rs, rowNum) -> new MachineLocatorFloorRow(
					rs.getString("floor_id"),
					rs.getString("name"),
					rs.getString("drawing_title"),
					rs.getString("image_url"),
					rs.getString("crop_json"),
					getDouble(rs, "page_w"),
					getDouble(rs, "page_h"),
					getDouble(rs, "ppp"),
					rs.getString("export_meta_json"),
					rs.getInt("version"),
					getDateTime(rs, "updated_at"),
					rs.getString("updated_by")
			);


	private static final String MACHINE_COLUMNS = """
			code,
			boxes_json,
			polys_json,
			fit_json,
			uncertain,
			src,
			review,
			cad_boxes_json,
			cad_polys_json,
			cad_fit_json
			""";


	private static final RowMapper<MachineLocatorMachineRow> MACHINE_ROW_MAPPER =
			(rs, rowNum) -> new MachineLocatorMachineRow(
					rs.getString("code"),
					rs.getString("boxes_json"),
					rs.getString("polys_json"),
					rs.getString("fit_json"),
					getBoolean(rs, "uncertain"),
					rs.getString("src"),
					getBoolean(rs, "review"),
					rs.getString("cad_boxes_json"),
					rs.getString("cad_polys_json"),
					rs.getString("cad_fit_json")
			);


	// =========================================================
	// FLOOR
	// =========================================================

	public List<MachineLocatorFloorRow> findFloors() {

		String sql = "SELECT " + FLOOR_COLUMNS + """
				FROM dbo.F2_MachineLocator_Floor
				ORDER BY floor_id
				""";

		return jdbcTemplate.query(
				sql,
				FLOOR_ROW_MAPPER
		);
	}

	public Optional<MachineLocatorFloorRow> findFloor(String floorId) {

		String sql = "SELECT " + FLOOR_COLUMNS + """
				FROM dbo.F2_MachineLocator_Floor
				WHERE floor_id = :floorId
				""";

		List<MachineLocatorFloorRow> rows =
				jdbcTemplate.query(
						sql,
						new MapSqlParameterSource("floorId", floorId),
						FLOOR_ROW_MAPPER
				);

		return rows.stream().findFirst();
	}

	/*
	 * Đọc version và giữ khóa UPDLOCK trên dòng floor tới hết transaction,
	 * để các request ghi cùng floor chạy tuần tự (kiểm tra ifVersion an toàn).
	 * Phải gọi bên trong @Transactional.
	 */
	public Optional<Integer> lockFloorVersion(String floorId) {

		String sql = """
				SELECT COALESCE(version, 0)
				FROM dbo.F2_MachineLocator_Floor WITH (UPDLOCK, HOLDLOCK)
				WHERE floor_id = :floorId
				""";

		List<Integer> rows =
				jdbcTemplate.queryForList(
						sql,
						new MapSqlParameterSource("floorId", floorId),
						Integer.class
				);

		return rows.stream().findFirst();
	}

	public int bumpVersion(
			String floorId,
			int expectedVersion,
			String updatedBy
	) {

		String sql = """
				UPDATE dbo.F2_MachineLocator_Floor
				SET version = :nextVersion,
				    updated_at = SYSDATETIME(),
				    updated_by = :updatedBy
				WHERE floor_id = :floorId
				  AND COALESCE(version, 0) = :expectedVersion
				""";

		return jdbcTemplate.update(
				sql,
				new MapSqlParameterSource()
						.addValue("floorId", floorId)
						.addValue("expectedVersion", expectedVersion)
						.addValue("nextVersion", expectedVersion + 1)
						.addValue("updatedBy", updatedBy)
		);
	}

	public void updateFloorLayout(
			String floorId,
			String cropJson,
			double pageW,
			double pageH,
			double ppp
	) {

		String sql = """
				UPDATE dbo.F2_MachineLocator_Floor
				SET crop_json = :cropJson,
				    page_w = :pageW,
				    page_h = :pageH,
				    ppp = :ppp
				WHERE floor_id = :floorId
				""";

		jdbcTemplate.update(
				sql,
				new MapSqlParameterSource()
						.addValue("floorId", floorId)
						.addValue("cropJson", cropJson)
						.addValue("pageW", pageW)
						.addValue("pageH", pageH)
						.addValue("ppp", ppp)
		);
	}


	// =========================================================
	// MACHINE
	// =========================================================

	public List<MachineLocatorMachineRow> findMachines(String floorId) {

		String sql = "SELECT " + MACHINE_COLUMNS + """
				FROM dbo.F2_MachineLocator_Machine
				WHERE floor_id = :floorId
				ORDER BY code
				""";

		return jdbcTemplate.query(
				sql,
				new MapSqlParameterSource("floorId", floorId),
				MACHINE_ROW_MAPPER
		);
	}

	public Optional<MachineLocatorMachineRow> findMachine(
			String floorId,
			String code
	) {

		String sql = "SELECT " + MACHINE_COLUMNS + """
				FROM dbo.F2_MachineLocator_Machine
				WHERE floor_id = :floorId
				  AND code = :code
				""";

		List<MachineLocatorMachineRow> rows =
				jdbcTemplate.query(
						sql,
						new MapSqlParameterSource()
								.addValue("floorId", floorId)
								.addValue("code", code),
						MACHINE_ROW_MAPPER
				);

		return rows.stream().findFirst();
	}

	/*
	 * Ghi đè toàn bộ cột của máy (kể cả cad_*), chưa có thì INSERT.
	 * Không cần MERGE vì dòng floor đã bị UPDLOCK (lockFloorVersion).
	 */
	public void saveMachine(
			String floorId,
			MachineLocatorMachineRow row,
			String updatedBy
	) {

		MapSqlParameterSource params =
				new MapSqlParameterSource()
						.addValue("floorId", floorId)
						.addValue("code", row.code())
						.addValue("boxesJson", row.boxesJson())
						.addValue("polysJson", row.polysJson())
						.addValue("fitJson", row.fitJson())
						.addValue("uncertain", row.uncertain())
						.addValue("src", row.src())
						.addValue("review", row.review())
						.addValue("cadBoxesJson", row.cadBoxesJson())
						.addValue("cadPolysJson", row.cadPolysJson())
						.addValue("cadFitJson", row.cadFitJson())
						.addValue("updatedBy", updatedBy);

		String updateSql = """
				UPDATE dbo.F2_MachineLocator_Machine
				SET boxes_json = :boxesJson,
				    polys_json = :polysJson,
				    fit_json = :fitJson,
				    uncertain = :uncertain,
				    src = :src,
				    review = :review,
				    cad_boxes_json = :cadBoxesJson,
				    cad_polys_json = :cadPolysJson,
				    cad_fit_json = :cadFitJson,
				    updated_at = SYSDATETIME(),
				    updated_by = :updatedBy
				WHERE floor_id = :floorId
				  AND code = :code
				""";

		if (jdbcTemplate.update(updateSql, params) > 0) {
			return;
		}

		String insertSql = """
				INSERT INTO dbo.F2_MachineLocator_Machine
				(
				    floor_id, code,
				    boxes_json, polys_json, fit_json,
				    uncertain, src, review,
				    cad_boxes_json, cad_polys_json, cad_fit_json,
				    created_at, updated_at, updated_by
				)
				VALUES
				(
				    :floorId, :code,
				    :boxesJson, :polysJson, :fitJson,
				    :uncertain, :src, :review,
				    :cadBoxesJson, :cadPolysJson, :cadFitJson,
				    SYSDATETIME(), SYSDATETIME(), :updatedBy
				)
				""";

		jdbcTemplate.update(insertSql, params);
	}

	public int deleteMachine(
			String floorId,
			String code
	) {

		String sql = """
				DELETE FROM dbo.F2_MachineLocator_Machine
				WHERE floor_id = :floorId
				  AND code = :code
				""";

		return jdbcTemplate.update(
				sql,
				new MapSqlParameterSource()
						.addValue("floorId", floorId)
						.addValue("code", code)
		);
	}

	public int renameMachine(
			String floorId,
			String fromCode,
			String toCode,
			String updatedBy
	) {

		String sql = """
				UPDATE dbo.F2_MachineLocator_Machine
				SET code = :toCode,
				    updated_at = SYSDATETIME(),
				    updated_by = :updatedBy
				WHERE floor_id = :floorId
				  AND code = :fromCode
				""";

		return jdbcTemplate.update(
				sql,
				new MapSqlParameterSource()
						.addValue("floorId", floorId)
						.addValue("fromCode", fromCode)
						.addValue("toCode", toCode)
						.addValue("updatedBy", updatedBy)
		);
	}


	// =========================================================
	// HISTORY
	// =========================================================

	public void insertHistory(
			String floorId,
			String code,
			String action,
			String beforeJson,
			String afterJson,
			String changedBy
	) {

		String sql = """
				INSERT INTO dbo.F2_MachineLocator_History
				(
				    floor_id, code, action,
				    before_json, after_json,
				    changed_by, changed_at
				)
				VALUES
				(
				    :floorId, :code, :action,
				    :beforeJson, :afterJson,
				    :changedBy, SYSDATETIME()
				)
				""";

		jdbcTemplate.update(
				sql,
				new MapSqlParameterSource()
						.addValue("floorId", floorId)
						.addValue("code", code)
						.addValue("action", action)
						.addValue("beforeJson", beforeJson)
						.addValue("afterJson", afterJson)
						.addValue("changedBy", changedBy)
		);
	}


	// =========================================================
	// NULLABLE COLUMN
	// =========================================================

	private static Boolean getBoolean(
			ResultSet rs,
			String column
	) throws SQLException {

		boolean value = rs.getBoolean(column);

		return rs.wasNull() ? null : value;
	}

	private static Double getDouble(
			ResultSet rs,
			String column
	) throws SQLException {

		double value = rs.getDouble(column);

		return rs.wasNull() ? null : value;
	}

	private static LocalDateTime getDateTime(
			ResultSet rs,
			String column
	) throws SQLException {

		Timestamp value = rs.getTimestamp(column);

		return value != null ? value.toLocalDateTime() : null;
	}
}
