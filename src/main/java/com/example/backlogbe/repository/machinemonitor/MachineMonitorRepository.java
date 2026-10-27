package com.example.backlogbe.repository.machinemonitor;

import com.example.backlogbe.model.MachineMonitorHseRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MachineMonitorRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;


	// Cột của dbo.F2_Patrol_Report (alias r), alias snake_case cho RowMapper
	private static final String HSE_REPORT_COLUMNS = """
			    LTRIM(RTRIM(r.machine)) AS machine_text,
			    LTRIM(RTRIM(r.division)) AS division,
			    r.id,
			    r.stt,
			    r.area,
			    r.riskTotal AS risk_total,
			    r.riskFreq AS risk_freq,
			    r.riskProb AS risk_prob,
			    r.riskSev AS risk_sev,
			    r.comment,
			    r.countermeasure,
			    r.patrol_user,
			    r.createdAt AS created_at,
			    r.pic,
			    r.dueDate AS due_date,
			    r.at_status,
			    r.at_user,
			    r.at_date,
			    r.at_comment,
			    r.hse_judge,
			    r.hse_user,
			    r.hse_date,
			    r.hse_comment,
			    r.imageNames AS image_names,
			    r.at_imageNames AS at_image_names,
			    r.hse_imageNames AS hse_image_names
			""";


	private static final RowMapper<MachineMonitorHseRow> HSE_ROW_MAPPER =
			(rs, rowNum) -> new MachineMonitorHseRow(
					rs.getString("machine_code"),
					rs.getString("machine_text"),
					rs.getString("division"),
					rs.getString("id"),
					rs.getString("stt"),
					rs.getString("area"),
					rs.getString("risk_total"),
					rs.getString("risk_freq"),
					rs.getString("risk_prob"),
					rs.getString("risk_sev"),
					rs.getString("comment"),
					rs.getString("countermeasure"),
					rs.getString("patrol_user"),
					getDateTime(rs, "created_at"),
					rs.getString("pic"),
					getDate(rs, "due_date"),
					rs.getString("at_status"),
					rs.getString("at_user"),
					getDateTime(rs, "at_date"),
					rs.getString("at_comment"),
					rs.getString("hse_judge"),
					rs.getString("hse_user"),
					getDateTime(rs, "hse_date"),
					rs.getString("hse_comment"),
					rs.getString("image_names"),
					rs.getString("at_image_names"),
					rs.getString("hse_image_names")
			);


	// =========================================================
	// HSE (PATROL)
	// =========================================================

	// Mã máy trong report có thể lệch hoa/thường, thừa khoảng trắng -> chuẩn hóa khi JOIN
	public List<MachineMonitorHseRow> findHseItems(
			String floorId,
			List<String> plants
	) {

		String sql = "SELECT m.code AS machine_code," + HSE_REPORT_COLUMNS + """
				FROM dbo.F2_Patrol_Report r
				JOIN dbo.F2_MachineLocator_Machine m
				    ON m.code = UPPER(LTRIM(RTRIM(r.machine)))
				   AND m.floor_id = :floorId
				WHERE r.plant IN (:plants)
				  AND r.type = 'Patrol'
				ORDER BY m.code
				""";

		return jdbcTemplate.query(
				sql,
				new MapSqlParameterSource()
						.addValue("floorId", floorId)
						.addValue("plants", plants),
				HSE_ROW_MAPPER
		);
	}

	/*
	 * Lỗi không khớp mã máy nào trên MỌI floor, thuộc các division của floor đang xem.
	 * machine NULL cũng lọt qua NOT EXISTS (so sánh NULL không khớp). machine_code luôn NULL.
	 */
	public List<MachineMonitorHseRow> findUnassignedHseItems(
			List<String> divisions,
			List<String> plants
	) {

		String sql = "SELECT CAST(NULL AS NVARCHAR(50)) AS machine_code," + HSE_REPORT_COLUMNS + """
				FROM dbo.F2_Patrol_Report r
				WHERE r.plant IN (:plants)
				  AND r.type = 'Patrol'
				  AND LTRIM(RTRIM(r.division)) IN (:divisions)
				  AND NOT EXISTS (
				      SELECT 1
				      FROM dbo.F2_MachineLocator_Machine m
				      WHERE m.code = UPPER(LTRIM(RTRIM(r.machine)))
				  )
				""";

		return jdbcTemplate.query(
				sql,
				new MapSqlParameterSource()
						.addValue("divisions", divisions)
						.addValue("plants", plants),
				HSE_ROW_MAPPER
		);
	}


	// =========================================================
	// NULLABLE COLUMN
	// =========================================================

	private static LocalDateTime getDateTime(
			ResultSet rs,
			String column
	) throws SQLException {

		Timestamp value = rs.getTimestamp(column);

		return value != null ? value.toLocalDateTime() : null;
	}

	// Cột DATETIME (vd dueDate) nhưng chỉ dùng phần ngày
	private static LocalDate getDate(
			ResultSet rs,
			String column
	) throws SQLException {

		Timestamp value = rs.getTimestamp(column);

		return value != null ? value.toLocalDateTime().toLocalDate() : null;
	}
}
