package com.example.backlogbe.assistant.screens.costmonitoring.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

// =========================================================
// LỊCH LÀM VIỆC + RATIO THEO NGÀY (dùng cho tool của trợ lý AI)
//
// Nguồn giống sp_CostMonitoringChart: [F2Database].[dbo].[F2_Working_Date]
//   WD_All    : 1 = nhà máy làm việc, 0 = nhà máy nghỉ (SP dùng cột này phân bổ PL)
//   WD_Office : 1 = văn phòng làm việc, 0 = văn phòng nghỉ
//   Adj_Ratio_PR / _MO / _GU : Ratio Sale&Inven của bộ phận (Fluct = FC × (1 + Ratio))
//   Adj_Ratio_KVH            : Ratio toàn nhà máy (chỉ để so sánh, SP không dùng)
// Ngày không có dòng -> không có trong map (SP coi là ngày làm việc).
// Giá trị không đọc được số -> null.
// =========================================================
@Repository
@RequiredArgsConstructor
public class CostWorkingDateRepository {

	private static final String WORKING_DAYS = """
			SELECT CAST(w.[Date] AS DATE)         AS D,
			       TRY_CONVERT(FLOAT, w.WD_All)    AS WD_All,
			       TRY_CONVERT(FLOAT, w.WD_Office) AS WD_Office
			FROM [F2Database].[dbo].[F2_Working_Date] w
			WHERE CAST(w.[Date] AS DATE) BETWEEN :fromDate AND :toDate
			""";

	private static final String RATIOS = """
			SELECT CAST(w.[Date] AS DATE)             AS D,
			       TRY_CONVERT(FLOAT, w.WD_All)        AS WD_All,
			       TRY_CONVERT(FLOAT, w.Adj_Ratio_PR)  AS Ratio_PR,
			       TRY_CONVERT(FLOAT, w.Adj_Ratio_MO)  AS Ratio_MO,
			       TRY_CONVERT(FLOAT, w.Adj_Ratio_GU)  AS Ratio_GU,
			       TRY_CONVERT(FLOAT, w.Adj_Ratio_KVH) AS Ratio_KVH
			FROM [F2Database].[dbo].[F2_Working_Date] w
			WHERE CAST(w.[Date] AS DATE) BETWEEN :fromDate AND :toDate
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public record WorkingDay(Double wdAll, Double wdOffice) {
	}

	// Ratio dạng số thập phân (0.05 = 5%), như trong bảng
	public record DailyRatio(Double wdAll, Double pr, Double mo, Double gu, Double kvh) {
	}

	public Map<LocalDate, WorkingDay> findWorkingDays(LocalDate from, LocalDate to) {

		Map<LocalDate, WorkingDay> result = new HashMap<>();

		jdbcTemplate.query(WORKING_DAYS, range(from, to), (RowCallbackHandler) rs -> {
			Date date = rs.getDate("D");
			if (date != null) {
				result.put(date.toLocalDate(), new WorkingDay(number(rs, "WD_All"), number(rs, "WD_Office")));
			}
		});

		return result;
	}

	// Sắp theo ngày tăng dần
	public TreeMap<LocalDate, DailyRatio> findRatios(LocalDate from, LocalDate to) {

		TreeMap<LocalDate, DailyRatio> result = new TreeMap<>();

		jdbcTemplate.query(RATIOS, range(from, to), (RowCallbackHandler) rs -> {
			Date date = rs.getDate("D");
			if (date != null) {
				result.put(date.toLocalDate(), new DailyRatio(
						number(rs, "WD_All"),
						number(rs, "Ratio_PR"),
						number(rs, "Ratio_MO"),
						number(rs, "Ratio_GU"),
						number(rs, "Ratio_KVH")
				));
			}
		});

		return result;
	}

	private static MapSqlParameterSource range(LocalDate from, LocalDate to) {
		return new MapSqlParameterSource()
				.addValue("fromDate", Date.valueOf(from), Types.DATE)
				.addValue("toDate", Date.valueOf(to), Types.DATE);
	}

	private static Double number(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		return value instanceof Number n ? n.doubleValue() : null;
	}
}
