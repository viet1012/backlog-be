package com.example.backlogbe.repository.costmonitoring;

import com.example.backlogbe.dto.costmonitoring.CostChartRowDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

/*
 * Luôn gọi object bằng tên đầy đủ [KVH].[dbo] / [F2Database].[dbo]
 * để không phụ thuộc database mặc định của datasource.
 */
@Repository
@RequiredArgsConstructor
public class CostMonitoringRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;


	private static final RowMapper<CostChartRowDto> CHART_ROW_MAPPER =
			(rs, rowNum) -> new CostChartRowDto(
					rs.getString("Div"),
					rs.getString("Chart"),
					getDate(rs, "D"),
					getDouble(rs, "FC_TGT"),
					getDouble(rs, "PL_TGT"),
					getDouble(rs, "BP"),
					getDouble(rs, "PL_MTD"),
					getDouble(rs, "Act_MTD"),
					getDouble(rs, "Est_MTD"),
					getDouble(rs, "Fluct_TGT")
			);


	// =========================================================
	// CHART
	// =========================================================

	// simuWeek null -> SP tự lấy tuần Simu mới nhất có số (theo từng Div / Cate)
	public List<CostChartRowDto> findChartRows(
			LocalDate monthStart,
			String simuWeek
	) {

		String sql = """
				EXEC [KVH].[dbo].[sp_CostMonitoringChart]
				    @MonthStart = :monthStart,
				    @SimuWeek = :simuWeek
				""";

		return jdbcTemplate.query(
				sql,
				new MapSqlParameterSource()
						.addValue("monthStart", Date.valueOf(monthStart), Types.DATE)
						.addValue("simuWeek", simuWeek, Types.NVARCHAR),
				CHART_ROW_MAPPER
		);
	}


	// =========================================================
	// MONTH
	// =========================================================

	// Dạng '2026.10', mới nhất trước
	public List<String> findAvailableMonths() {

		String sql = """
				SELECT DISTINCT [Month]
				FROM [F2Database].[dbo].[F2_PL_TGT]
				ORDER BY [Month] DESC
				""";

		return jdbcTemplate.queryForList(
				sql,
				new MapSqlParameterSource(),
				String.class
		);
	}


	// =========================================================
	// PL SIMULATION WEEK
	// =========================================================

	// Một dòng = 1 khung (Div, Cate) có số ở tuần Simu đó trong tháng.
	public record PlWeekRow(
			String div,
			String cate,
			String dataType
	) {
	}

	/*
	 * Tuần Simu có số theo từng khung – cùng điều kiện với LatestSimu trong sp_CostMonitoringChart:
	 * gom theo (Div, Cate, DataType), tổng KUsd <> 0; chỉ 9 khung màn hình dùng
	 * (Div PRESS/MOLD/GUIDE × Cate OverTime/Tools/RM).
	 * month dạng '2026.10'.
	 */
	public List<PlWeekRow> findPlWeekRows(String month) {

		String sql = """
				SELECT RTRIM([Div]) AS Div, RTRIM([Cate]) AS Cate, RTRIM([DataType]) AS DataType
				FROM [F2Database].[dbo].[F2_PL_TGT]
				WHERE [Month] = :month
				  AND RTRIM([DataType]) LIKE N'Simu[_]W%'
				  AND RTRIM([Div]) IN (N'PRESS', N'MOLD', N'GUIDE')
				  AND RTRIM([Cate]) IN (N'OverTime', N'Tools', N'RM')
				GROUP BY RTRIM([Div]), RTRIM([Cate]), RTRIM([DataType])
				HAVING ISNULL(SUM([KUsd]), 0) <> 0
				""";

		return jdbcTemplate.query(
				sql,
				new MapSqlParameterSource()
						.addValue("month", month, Types.NVARCHAR),
				(rs, rowNum) -> new PlWeekRow(
						rs.getString("Div"),
						rs.getString("Cate"),
						rs.getString("DataType")
				)
		);
	}


	// =========================================================
	// NULLABLE COLUMN
	// =========================================================

	// getObject để giữ NULL; DECIMAL trả BigDecimal, FLOAT trả Double
	private static Double getDouble(
			ResultSet rs,
			String column
	) throws SQLException {

		Object value = rs.getObject(column);

		return value instanceof Number number ? number.doubleValue() : null;
	}

	private static LocalDate getDate(
			ResultSet rs,
			String column
	) throws SQLException {

		Date value = rs.getDate(column);

		return value != null ? value.toLocalDate() : null;
	}
}
