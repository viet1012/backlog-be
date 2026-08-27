package com.example.backlogbe.repository.backlog;


import com.example.backlogbe.dto.backlog.BacklogFilterItem;
import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class BacklogFilterSqlBuilder {

	private final BacklogColumnMetadataProvider metadataProvider;


	public QueryParts build(
			BacklogFilterRequest request
	) {

		if (
				request == null
						|| request.filters() == null
						|| request.filters().isEmpty()
		) {
			return QueryParts.empty();
		}

		String separator =
				resolveSeparator(
						request.logicOperator()
				);

		List<String> conditions =
				new ArrayList<>();

		List<Object> params =
				new ArrayList<>();


		for (
				BacklogFilterItem filter :
				request.filters()
		) {

			if (
					filter == null
							|| blank(filter.field())
							|| blank(filter.operator())
			) {
				continue;
			}

			var column =
					metadataProvider.get(
							filter.field()
					);

			String condition =
					switch (
							column.type()
							) {

						case TEXT -> buildText(
								quote(column.name()),
								filter,
								params
						);

						case NUMBER -> buildNumber(
								quote(column.name()),
								filter,
								params
						);

						case DATE -> buildDate(
								quote(column.name()),
								filter,
								params
						);
					};

			if (
					condition != null
							&& !condition.isBlank()
			) {
				conditions.add(condition);
			}
		}


		if (conditions.isEmpty()) {
			return QueryParts.empty();
		}

		return new QueryParts(
				" WHERE "
						+ String.join(
						separator,
						conditions
				),
				params
		);
	}


	private String buildText(
			String column,
			BacklogFilterItem filter,
			List<Object> params
	) {

		String operator =
				normalize(filter.operator());

		String value =
				filter.value() == null
						? ""
						: filter.value().trim();


		return switch (operator) {

			// =====================================================
			// EXCEL CHECKBOX FILTER
			// =====================================================

			case "in", "isanyof" -> {

				List<String> values =
						filter.values() == null
								? List.of()
								: filter.values()
								.stream()
								.filter(v -> v != null)
								.map(String::trim)
								.distinct()
								.toList();


				if (values.isEmpty()) {
					yield null;
				}


				boolean includeBlank =
						values.stream()
								.anyMatch(String::isEmpty);


				List<String> nonBlankValues =
						values.stream()
								.filter(v -> !v.isEmpty())
								.toList();


				List<String> parts =
						new ArrayList<>();


				if (!nonBlankValues.isEmpty()) {

					String placeholders =
							String.join(
									",",
									java.util.Collections.nCopies(
											nonBlankValues.size(),
											"?"
									)
							);

					parts.add(
							column
									+ " IN ("
									+ placeholders
									+ ")"
					);

					params.addAll(nonBlankValues);
				}


				if (includeBlank) {

					parts.add(
							"("
									+ column
									+ " IS NULL OR "
									+ column
									+ " = '')"
					);
				}


				if (parts.isEmpty()) {
					yield null;
				}


				yield "("
						+ String.join(
						" OR ",
						parts
				)
						+ ")";
			}


			// =====================================================
			// NORMAL TEXT FILTER
			// =====================================================

			case "contains" -> {
				params.add("%" + value + "%");

				yield column + " LIKE ?";
			}


			case "doesnotcontain" -> {
				params.add("%" + value + "%");

				yield "("
						+ column
						+ " NOT LIKE ? OR "
						+ column
						+ " IS NULL)";
			}


			case "equals", "is", "=" -> {
				params.add(value);

				yield column + " = ?";
			}


			case "doesnotequal", "not", "!=" -> {
				params.add(value);

				yield "("
						+ column
						+ " <> ? OR "
						+ column
						+ " IS NULL)";
			}


			case "startswith" -> {
				params.add(value + "%");

				yield column + " LIKE ?";
			}


			case "endswith" -> {
				params.add("%" + value);

				yield column + " LIKE ?";
			}


			case "isempty" -> "("
					+ column
					+ " IS NULL OR "
					+ column
					+ " = '')";


			case "isnotempty" -> "("
					+ column
					+ " IS NOT NULL AND "
					+ column
					+ " <> '')";


			default -> throw unsupported(
					"text",
					filter.operator()
			);
		};
	}

	private String buildNumber(
			String column,
			BacklogFilterItem filter,
			List<Object> params
	) {

		String operator =
				normalize(
						filter.operator()
				);
		// =========================================================
		// EXCEL MULTI VALUE FILTER
		// =========================================================

		if (
				"in".equals(operator)
						|| "isanyof".equals(operator)
		) {

			if (
					filter.values() == null
							|| filter.values().isEmpty()
			) {
				return null;
			}


			List<BigDecimal> values =
					filter.values()
							.stream()
							.filter(v ->
									v != null
											&& !v.isBlank()
							)
							.map(String::trim)
							.map(v -> {
								try {
									return new BigDecimal(v);
								} catch (NumberFormatException ex) {
									throw new IllegalArgumentException(
											"Invalid number for "
													+ filter.field()
													+ ": "
													+ v
									);
								}
							})
							.distinct()
							.toList();


			boolean includeNull =
					filter.values()
							.stream()
							.anyMatch(v ->
									v == null
											|| v.isBlank()
							);


			List<String> parts =
					new ArrayList<>();


			if (!values.isEmpty()) {

				String placeholders =
						String.join(
								",",
								java.util.Collections.nCopies(
										values.size(),
										"?"
								)
						);

				parts.add(
						column
								+ " IN ("
								+ placeholders
								+ ")"
				);

				params.addAll(values);
			}


			if (includeNull) {
				parts.add(
						column + " IS NULL"
				);
			}


			if (parts.isEmpty()) {
				return null;
			}


			return "("
					+ String.join(
					" OR ",
					parts
			)
					+ ")";
		}
		if ("isempty".equals(operator)) {
			return column + " IS NULL";
		}

		if ("isnotempty".equals(operator)) {
			return column + " IS NOT NULL";
		}

		if (blank(filter.value())) {
			return null;
		}

		BigDecimal value;

		try {
			value =
					new BigDecimal(
							filter.value().trim()
					);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(
					"Invalid number for "
							+ filter.field()
							+ ": "
							+ filter.value()
			);
		}

		return switch (operator) {

			case "=", "equals", "is" -> {
				params.add(value);
				yield column + " = ?";
			}

			case "!=", "doesnotequal", "not" -> {
				params.add(value);

				yield "("
						+ column
						+ " <> ? OR "
						+ column
						+ " IS NULL)";
			}

			case ">" -> {
				params.add(value);
				yield column + " > ?";
			}

			case ">=" -> {
				params.add(value);
				yield column + " >= ?";
			}

			case "<" -> {
				params.add(value);
				yield column + " < ?";
			}

			case "<=" -> {
				params.add(value);
				yield column + " <= ?";
			}

			default -> throw unsupported(
					"number",
					filter.operator()
			);
		};
	}


	private String buildDate(
			String column,
			BacklogFilterItem filter,
			List<Object> params
	) {

		String operator =
				normalize(
						filter.operator()
				);
		// =========================================================
		// EXCEL DATE MULTI VALUE FILTER
		// =========================================================

		if (
				"in".equals(operator)
						|| "isanyof".equals(operator)
		) {

			if (
					filter.values() == null
							|| filter.values().isEmpty()
			) {
				return null;
			}


			List<String> parts =
					new ArrayList<>();


			for (String raw : filter.values()) {

				// BLANK
				if (
						raw == null
								|| raw.isBlank()
				) {

					parts.add(
							column + " IS NULL"
					);

					continue;
				}


				LocalDate selectedDate;

				try {

					// hỗ trợ cả:
					// 2026-08-26
					// 2026-08-26T08:30:00

					String normalizedValue =
							raw.length() >= 10
									? raw.substring(0, 10)
									: raw;

					selectedDate =
							LocalDate.parse(
									normalizedValue
							);

				} catch (Exception ex) {

					throw new IllegalArgumentException(
							"Invalid date for "
									+ filter.field()
									+ ": "
									+ raw
					);
				}


				LocalDateTime start =
						selectedDate.atStartOfDay();

				LocalDateTime next =
						selectedDate
								.plusDays(1)
								.atStartOfDay();


				parts.add(
						"("
								+ column
								+ " >= ? AND "
								+ column
								+ " < ?)"
				);


				params.add(
						Timestamp.valueOf(start)
				);

				params.add(
						Timestamp.valueOf(next)
				);
			}


			if (parts.isEmpty()) {
				return null;
			}


			return "("
					+ String.join(
					" OR ",
					parts
			)
					+ ")";
		}

		if ("isempty".equals(operator)) {
			return column + " IS NULL";
		}

		if ("isnotempty".equals(operator)) {
			return column + " IS NOT NULL";
		}

		if (blank(filter.value())) {
			return null;
		}

		LocalDate date;

		try {
			date =
					LocalDate.parse(
							filter.value().trim()
					);
		} catch (Exception ex) {
			throw new IllegalArgumentException(
					"Invalid date for "
							+ filter.field()
							+ ": "
							+ filter.value()
			);
		}

		LocalDateTime start =
				date.atStartOfDay();

		LocalDateTime nextDay =
				date.plusDays(1)
						.atStartOfDay();

		return switch (operator) {

			case "is", "equals", "=" -> {
				params.add(
						Timestamp.valueOf(start)
				);

				params.add(
						Timestamp.valueOf(nextDay)
				);

				yield "("
						+ column
						+ " >= ? AND "
						+ column
						+ " < ?)";
			}

			case "not", "doesnotequal", "!=" -> {
				params.add(
						Timestamp.valueOf(start)
				);

				params.add(
						Timestamp.valueOf(nextDay)
				);

				yield "("
						+ column
						+ " < ? OR "
						+ column
						+ " >= ? OR "
						+ column
						+ " IS NULL)";
			}

			case "after" -> {
				params.add(
						Timestamp.valueOf(nextDay)
				);

				yield column + " >= ?";
			}

			case "onorafter" -> {
				params.add(
						Timestamp.valueOf(start)
				);

				yield column + " >= ?";
			}

			case "before" -> {
				params.add(
						Timestamp.valueOf(start)
				);

				yield column + " < ?";
			}

			case "onorbefore" -> {
				params.add(
						Timestamp.valueOf(nextDay)
				);

				yield column + " < ?";
			}

			default -> throw unsupported(
					"date",
					filter.operator()
			);
		};
	}


	private String resolveSeparator(
			String logicOperator
	) {

		if (
				logicOperator == null
						|| logicOperator.isBlank()
						|| "and".equalsIgnoreCase(
						logicOperator
				)
		) {
			return " AND ";
		}

		if (
				"or".equalsIgnoreCase(
						logicOperator
				)
		) {
			return " OR ";
		}

		throw new IllegalArgumentException(
				"Unsupported logicOperator: "
						+ logicOperator
		);
	}


	private String quote(
			String column
	) {
		return "[" + column + "]";
	}


	private String normalize(
			String value
	) {

		return value == null
				? ""
				: value
				.trim()
				.replace(" ", "")
				.toLowerCase(
						Locale.ROOT
				);
	}


	private boolean blank(
			String value
	) {
		return value == null
				|| value.isBlank();
	}


	private IllegalArgumentException unsupported(
			String type,
			String operator
	) {

		return new IllegalArgumentException(
				"Unsupported "
						+ type
						+ " operator: "
						+ operator
		);
	}


	public record QueryParts(
			String where,
			List<Object> params
	) {

		public static QueryParts empty() {
			return new QueryParts(
					"",
					List.of()
			);
		}
	}
}