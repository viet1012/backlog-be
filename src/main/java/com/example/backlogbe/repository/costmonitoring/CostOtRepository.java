package com.example.backlogbe.repository.costmonitoring;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/*
 * [KVH].[dbo].[CostOtDaily] – Actual OT theo ngày (sql/07_cost_ot_daily.sql).
 * Gọi bằng tên đầy đủ để không phụ thuộc database mặc định của datasource.
 */
@Repository
@RequiredArgsConstructor
public class CostOtRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public record OtRow(
			LocalDate date,
			String subDiv,
			BigDecimal amountK
	) {
	}

	public record StatusRow(
			String subDiv,
			int days,
			LocalDate lastDate,
			Double totalK,
			LocalDateTime loadedAt,
			String source
	) {
	}


	// Xóa toàn bộ dòng của (tháng, SubDiv) rồi ghi lại – caller bọc trong 1 transaction
	public int replaceMonth(
			YearMonth month,
			String subDiv,
			List<OtRow> rows,
			String source,
			String loadedBy
	) {

		jdbcTemplate.update(
				"""
				DELETE FROM [KVH].[dbo].[CostOtDaily]
				WHERE SubDiv = :subDiv AND D >= :fromD AND D <= :toD
				""",
				new MapSqlParameterSource()
						.addValue("subDiv", subDiv, Types.NVARCHAR)
						.addValue("fromD", Date.valueOf(month.atDay(1)), Types.DATE)
						.addValue("toD", Date.valueOf(month.atEndOfMonth()), Types.DATE)
		);

		if (rows.isEmpty()) {
			return 0;
		}

		SqlParameterSource[] batch = rows.stream()
				.map(row -> new MapSqlParameterSource()
						.addValue("d", Date.valueOf(row.date()), Types.DATE)
						.addValue("subDiv", row.subDiv(), Types.NVARCHAR)
						.addValue("amt", row.amountK(), Types.DECIMAL)
						.addValue("source", source, Types.NVARCHAR)
						.addValue("loadedBy", loadedBy, Types.NVARCHAR))
				.toArray(SqlParameterSource[]::new);

		jdbcTemplate.batchUpdate(
				"""
				INSERT INTO [KVH].[dbo].[CostOtDaily] (D, SubDiv, AmtKUsd, Source, LoadedBy, LoadedAt)
				VALUES (:d, :subDiv, :amt, :source, :loadedBy, SYSDATETIME())
				""",
				batch
		);

		return rows.size();
	}

	// 1 dòng / SubDiv có dữ liệu trong tháng
	public List<StatusRow> findStatus(YearMonth month) {

		return jdbcTemplate.query(
				"""
				SELECT o.SubDiv,
				       COUNT(*)         AS Days,
				       MAX(o.D)         AS LastD,
				       SUM(o.AmtKUsd)   AS TotalK,
				       MAX(o.LoadedAt)  AS LoadedAt,
				       (SELECT TOP (1) x.Source
				        FROM [KVH].[dbo].[CostOtDaily] x
				        WHERE x.SubDiv = o.SubDiv AND x.D >= :fromD AND x.D <= :toD
				        ORDER BY x.LoadedAt DESC) AS Source
				FROM [KVH].[dbo].[CostOtDaily] o
				WHERE o.D >= :fromD AND o.D <= :toD
				GROUP BY o.SubDiv
				""",
				new MapSqlParameterSource()
						.addValue("fromD", Date.valueOf(month.atDay(1)), Types.DATE)
						.addValue("toD", Date.valueOf(month.atEndOfMonth()), Types.DATE),
				(rs, rowNum) -> {
					Date last = rs.getDate("LastD");
					Timestamp loadedAt = rs.getTimestamp("LoadedAt");
					BigDecimal total = rs.getBigDecimal("TotalK");
					return new StatusRow(
							rs.getString("SubDiv"),
							rs.getInt("Days"),
							last != null ? last.toLocalDate() : null,
							total != null ? total.doubleValue() : null,
							loadedAt != null ? loadedAt.toLocalDateTime() : null,
							rs.getString("Source")
					);
				}
		);
	}
}
