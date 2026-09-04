package com.example.backlogbe.repository.facconfirm;

import com.example.backlogbe.dto.facconfirm.FacConfirmFilterItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;


@Component
@RequiredArgsConstructor
public class FacConfirmFilterSqlBuilder {


	private final FacConfirmColumnMetadataProvider metadataProvider;


	// =========================================================
	// BUILD
	// =========================================================

	public QueryParts build(
			List<FacConfirmFilterItem> filters,
			String logicOperator
	) {

		if (
				filters == null
						|| filters.isEmpty()
		) {

			return QueryParts.empty();
		}


		String separator =
				resolveSeparator(
						logicOperator
				);


		List<String> conditions =
				new ArrayList<>();


		List<Object> params =
				new ArrayList<>();


		for (
				FacConfirmFilterItem filter
				: filters
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


			String sqlColumn =
					quote(
							column.name()
					);


			String condition =
					switch (
							column.type()
							) {

						case TEXT -> buildText(
								sqlColumn,
								filter,
								params
						);


						case NUMBER -> buildNumber(
								sqlColumn,
								filter,
								params
						);


						case DATE -> buildDate(
								sqlColumn,
								filter,
								params
						);
					};


			if (
					condition != null
							&& !condition.isBlank()
			) {

				conditions.add(
						condition
				);
			}
		}


		if (conditions.isEmpty()) {

			return QueryParts.empty();
		}


		/*
		 * BASE_FROM_WHERE của FacConfirm đã có WHERE,
		 * nên ở đây append AND (...), không tạo WHERE mới.
		 */
		return new QueryParts(

				" AND ("
						+ String.join(
						separator,
						conditions
				)
						+ ")",

				params
		);
	}


	// =========================================================
	// TEXT
	// =========================================================

	private String buildText(
			String column,
			FacConfirmFilterItem filter,
			List<Object> params
	) {

		String operator =
				normalize(
						filter.operator()
				);


		String value =
				filter.value() == null
						? ""
						: filter.value().trim();


		return switch (operator) {


			// =================================================
			// EXCEL MULTI SELECT
			// =================================================

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
								.anyMatch(
										String::isEmpty
								);


				List<String> nonBlankValues =
						values.stream()
								.filter(
										v -> !v.isEmpty()
								)
								.toList();


				List<String> parts =
						new ArrayList<>();


				if (!nonBlankValues.isEmpty()) {

					String placeholders =
							String.join(
									",",
									Collections.nCopies(
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


					params.addAll(
							nonBlankValues
					);
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


			case "contains" -> {

				params.add(
						"%"
								+ value
								+ "%"
				);

				yield column
						+ " LIKE ?";
			}


			case "doesnotcontain" -> {

				params.add(
						"%"
								+ value
								+ "%"
				);

				yield "("
						+ column
						+ " NOT LIKE ? OR "
						+ column
						+ " IS NULL)";
			}


			case "equals", "is", "=" -> {

				params.add(
						value
				);

				yield column
						+ " = ?";
			}


			case "doesnotequal", "not", "!=" -> {

				params.add(
						value
				);

				yield "("
						+ column
						+ " <> ? OR "
						+ column
						+ " IS NULL)";
			}


			case "startswith" -> {

				params.add(
						value
								+ "%"
				);

				yield column
						+ " LIKE ?";
			}


			case "endswith" -> {

				params.add(
						"%"
								+ value
				);

				yield column
						+ " LIKE ?";
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


	// =========================================================
	// NUMBER
	// =========================================================

	private String buildNumber(
			String column,
			FacConfirmFilterItem filter,
			List<Object> params
	) {

		String operator =
				normalize(
						filter.operator()
				);


		// =====================================================
		// EXCEL MULTI SELECT
		// =====================================================

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

							.filter(
									value ->
											value != null
													&& !value.isBlank()
							)

							.map(
									String::trim
							)

							.map(
									value -> {

										try {

											return new BigDecimal(
													value
											);

										} catch (
												NumberFormatException ex
										) {

											throw new IllegalArgumentException(
													"Invalid number for "
															+ filter.field()
															+ ": "
															+ value
											);
										}
									}
							)

							.distinct()

							.toList();


			boolean includeNull =
					filter.values()
							.stream()
							.anyMatch(
									value ->
											value == null
													|| value.isBlank()
							);


			List<String> parts =
					new ArrayList<>();


			if (!values.isEmpty()) {

				String placeholders =
						String.join(
								",",
								Collections.nCopies(
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


				params.addAll(
						values
				);
			}


			if (includeNull) {

				parts.add(
						column
								+ " IS NULL"
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

			return column
					+ " IS NULL";
		}


		if ("isnotempty".equals(operator)) {

			return column
					+ " IS NOT NULL";
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

		} catch (
				NumberFormatException ex
		) {

			throw new IllegalArgumentException(
					"Invalid number for "
							+ filter.field()
							+ ": "
							+ filter.value()
			);
		}


		return switch (operator) {

			case "=", "equals", "is" -> {

				params.add(
						value
				);

				yield column
						+ " = ?";
			}


			case "!=", "doesnotequal", "not" -> {

				params.add(
						value
				);

				yield "("
						+ column
						+ " <> ? OR "
						+ column
						+ " IS NULL)";
			}


			case ">" -> {

				params.add(value);

				yield column
						+ " > ?";
			}


			case ">=" -> {

				params.add(value);

				yield column
						+ " >= ?";
			}


			case "<" -> {

				params.add(value);

				yield column
						+ " < ?";
			}


			case "<=" -> {

				params.add(value);

				yield column
						+ " <= ?";
			}


			default -> throw unsupported(
					"number",
					filter.operator()
			);
		};
	}


// =========================================================
// DATE
// =========================================================

	private String buildDate(
			String column,
			FacConfirmFilterItem filter,
			List<Object> params
	) {

		String operator =
				normalize(
						filter.operator()
				);


		// =====================================================
		// EXCEL DATE MULTI SELECT
		//
		// Frontend values:
		//
		// 2026-09-01
		// 2026-09-02
		// 2026-09-03
		//
		// SQL column:
		// DATE / DATETIME / DATETIME2
		// =====================================================

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


			boolean blankAdded =
					false;


			for (
					String raw :
					filter.values()
			) {

				// =================================================
				// NULL / BLANK
				// =================================================

				if (
						raw == null
								|| raw.isBlank()
				) {

					if (!blankAdded) {

						parts.add(
								column
										+ " IS NULL"
						);

						blankAdded = true;
					}

					continue;
				}


				// =================================================
				// DATE
				// =================================================

				LocalDate selectedDate =
						parseDate(
								raw,
								filter.field()
						);


				LocalDateTime start =
						selectedDate
								.atStartOfDay();


				LocalDateTime nextDay =
						selectedDate
								.plusDays(1)
								.atStartOfDay();


				/*
				 * Không CAST column trong WHERE.
				 *
				 * Giữ:
				 *
				 * [IssueD] >= ?
				 * AND
				 * [IssueD] < ?
				 *
				 * tốt hơn cho index.
				 */

				parts.add(
						"("
								+ column
								+ " >= ? AND "
								+ column
								+ " < ?)"
				);


				params.add(
						Timestamp.valueOf(
								start
						)
				);


				params.add(
						Timestamp.valueOf(
								nextDay
						)
				);
			}


			if (
					parts.isEmpty()
			) {

				return null;
			}


			return "("
					+ String.join(
					" OR ",
					parts
			)
					+ ")";
		}


		// =====================================================
		// EMPTY
		// =====================================================

		if (
				"isempty".equals(
						operator
				)
		) {

			return column
					+ " IS NULL";
		}


		// =====================================================
		// NOT EMPTY
		// =====================================================

		if (
				"isnotempty".equals(
						operator
				)
		) {

			return column
					+ " IS NOT NULL";
		}


		// =====================================================
		// NO VALUE
		// =====================================================

		if (
				blank(
						filter.value()
				)
		) {

			return null;
		}


		// =====================================================
		// SINGLE DATE
		// =====================================================

		LocalDate date =
				parseDate(
						filter.value(),
						filter.field()
				);


		LocalDateTime start =
				date
						.atStartOfDay();


		LocalDateTime nextDay =
				date
						.plusDays(1)
						.atStartOfDay();


		// =====================================================
		// OPERATORS
		// =====================================================

		return switch (
				operator
				) {

			// =================================================
			// EQUAL DATE
			//
			// 2026-09-03:
			//
			// >= 2026-09-03 00:00
			// <  2026-09-04 00:00
			// =================================================

			case "is",
			     "equals",
			     "=" -> {

				params.add(
						Timestamp.valueOf(
								start
						)
				);


				params.add(
						Timestamp.valueOf(
								nextDay
						)
				);


				yield "("
						+ column
						+ " >= ? AND "
						+ column
						+ " < ?)";
			}


			// =================================================
			// NOT EQUAL
			// =================================================

			case "not",
			     "doesnotequal",
			     "!=" -> {

				params.add(
						Timestamp.valueOf(
								start
						)
				);


				params.add(
						Timestamp.valueOf(
								nextDay
						)
				);


				yield "("
						+ column
						+ " < ? OR "
						+ column
						+ " >= ? OR "
						+ column
						+ " IS NULL)";
			}


			// =================================================
			// AFTER
			//
			// after 2026-09-03
			// =>
			// >= 2026-09-04 00:00
			// =================================================

			case "after" -> {

				params.add(
						Timestamp.valueOf(
								nextDay
						)
				);


				yield column
						+ " >= ?";
			}


			// =================================================
			// ON OR AFTER
			// =================================================

			case "onorafter" -> {

				params.add(
						Timestamp.valueOf(
								start
						)
				);


				yield column
						+ " >= ?";
			}


			// =================================================
			// BEFORE
			// =================================================

			case "before" -> {

				params.add(
						Timestamp.valueOf(
								start
						)
				);


				yield column
						+ " < ?";
			}


			// =================================================
			// ON OR BEFORE
			//
			// <= toàn bộ ngày được chọn
			// =>
			// < đầu ngày kế tiếp
			// =================================================

			case "onorbefore" -> {

				params.add(
						Timestamp.valueOf(
								nextDay
						)
				);


				yield column
						+ " < ?";
			}


			default -> throw unsupported(
					"date",
					filter.operator()
			);
		};
	}


// =========================================================
// DATE PARSER
// =========================================================

	private LocalDate parseDate(
			String raw,
			String field
	) {

		if (
				raw == null
						|| raw.isBlank()
		) {

			throw new IllegalArgumentException(
					"Date value is required for "
							+ field
			);
		}


		String value =
				raw.trim();


		// =====================================================
		// yyyy-MM-dd
		// =====================================================

		try {

			return LocalDate.parse(
					value
			);

		} catch (
				Exception ignored
		) {
			// Try ISO DateTime below.
		}


		// =====================================================
		// ISO DATETIME
		//
		// 2026-09-03T08:30:00
		// 2026-09-03 08:30:00
		//
		// Chỉ lấy yyyy-MM-dd.
		// =====================================================

		if (
				value.length() >= 10
						&& value.charAt(4) == '-'
						&& value.charAt(7) == '-'
		) {

			try {

				return LocalDate.parse(
						value.substring(
								0,
								10
						)
				);

			} catch (
					Exception ignored
			) {
				// Throw below.
			}
		}


		throw new IllegalArgumentException(
				"Invalid date for "
						+ field
						+ ": "
						+ raw
						+ ". Expected yyyy-MM-dd."
		);
	}

	// =========================================================
	// LOGIC
	// =========================================================

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


	// =========================================================
	// UTIL
	// =========================================================

	private String quote(
			String column
	) {

		return "["
				+ column
				+ "]";
	}


	private String normalize(
			String value
	) {

		return value == null
				? ""
				: value
				.trim()
				.replace(
						" ",
						""
				)
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


	// =========================================================
	// QUERY PARTS
	// =========================================================

	public record QueryParts(
			String sql,
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