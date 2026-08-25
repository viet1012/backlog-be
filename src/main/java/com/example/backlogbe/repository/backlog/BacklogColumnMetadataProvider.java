package com.example.backlogbe.repository.backlog;


import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class BacklogColumnMetadataProvider {

	private final JdbcTemplate jdbcTemplate;

	private final Map<String, ColumnMeta> columns =
			new HashMap<>();


	@PostConstruct
	public void load() {

		columns.clear();

		jdbcTemplate.query(
				"""
						SELECT TOP 0 *
						FROM F2_Backlog_Main
						""",
				(ResultSetExtractor<Void>) rs -> {

					ResultSetMetaData meta =
							rs.getMetaData();

					for (
							int i = 1;
							i <= meta.getColumnCount();
							i++
					) {

						String name =
								meta.getColumnName(i);

						String sqlType =
								meta.getColumnTypeName(i);

						columns.put(
								normalize(name),
								new ColumnMeta(
										name,
										resolveType(sqlType)
								)
						);
					}

					return null;
				}
		);

		if (columns.isEmpty()) {
			throw new IllegalStateException(
					"Unable to load F2_Backlog_Main metadata"
			);
		}
	}


	public ColumnMeta get(
			String field
	) {

		if (
				field == null
						|| field.isBlank()
		) {
			throw new IllegalArgumentException(
					"Filter field is required"
			);
		}

		ColumnMeta meta =
				columns.get(
						normalize(field)
				);

		if (meta == null) {
			throw new IllegalArgumentException(
					"Unsupported filter field: "
							+ field
			);
		}

		return meta;
	}


	private String normalize(
			String value
	) {

		return value
				.trim()
				.toLowerCase(
						Locale.ROOT
				);
	}


	private ColumnType resolveType(
			String sqlType
	) {

		if (sqlType == null) {
			return ColumnType.TEXT;
		}

		return switch (
				sqlType.toLowerCase(
						Locale.ROOT
				)
				) {

			case "tinyint",
			     "smallint",
			     "int",
			     "bigint",
			     "decimal",
			     "numeric",
			     "float",
			     "real",
			     "money",
			     "smallmoney",
			     "bit" -> ColumnType.NUMBER;

			case "date",
			     "datetime",
			     "datetime2",
			     "smalldatetime",
			     "datetimeoffset",
			     "time" -> ColumnType.DATE;

			default -> ColumnType.TEXT;
		};
	}


	public enum ColumnType {
		TEXT,
		NUMBER,
		DATE
	}


	public record ColumnMeta(
			String name,
			ColumnType type
	) {
	}
}