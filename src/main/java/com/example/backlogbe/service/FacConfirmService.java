package com.example.backlogbe.service;

import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.dto.facconfirm.*;
import com.example.backlogbe.repository.facconfirm.FacConfirmFilterField;
import com.example.backlogbe.repository.facconfirm.FacConfirmRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class FacConfirmService {


	// =========================================================
	// CONFIG
	// =========================================================

	private static final Set<String> VALID_PROCESS_GROUPS =
			Set.of(
					"Fine",
					"Heat",
					"Rough"
			);


	private static final int DEFAULT_PAGE_SIZE = 20;

	private static final int MAX_PAGE_SIZE = 200;

	private static final int MAX_FILTERS = 50;


	private final FacConfirmRepository repository;


	// =========================================================
	// DETAIL - OLD GET API
	// =========================================================

	@Transactional(readOnly = true)
	public PageResponse<FacConfirmDto> getFacConfirm(
			String div,
			LocalDate expD,
			String procGrp,
			String classify,
			int page,
			int size
	) {

		String safeDiv =
				normalizeDiv(
						div
				);


		validateExportDate(
				expD
		);


		String safeProcGrp =
				normalizeProcessGroup(
						procGrp
				);


		String safeClassify =
				normalizeClassify(
						classify
				);


		int safePage =
				normalizePage(
						page
				);


		int safeSize =
				normalizeSize(
						size
				);


		// =====================================================
		// COUNT
		// =====================================================

		long total =
				repository.count(
						safeDiv,
						expD,
						safeProcGrp,
						safeClassify
				);


		// =====================================================
		// DATA
		// =====================================================

		List<FacConfirmDto> content =
				repository.findPage(
						safeDiv,
						expD,
						safeProcGrp,
						safeClassify,
						safePage,
						safeSize
				);


		return PageResponse.of(
				content,
				safePage,
				safeSize,
				total
		);
	}

	private String normalizeClassify(
			String classify
	) {

		if (
				classify == null
						|| classify.isBlank()
		) {
			return null;
		}


		String value =
				classify.trim();


		if (
				value.equalsIgnoreCase("Sale")
		) {
			return "Sale";
		}


		if (
				value.equalsIgnoreCase("Stock")
		) {
			return "Stock";
		}


		throw new IllegalArgumentException(
				"classify must be Sale or Stock"
		);
	}

	// =========================================================
	// SEARCH - SERVER SIDE EXCEL FILTER
	// =========================================================

	@Transactional(readOnly = true)
	public PageResponse<FacConfirmDto> search(
			FacConfirmSearchRequest request
	) {

		// =====================================================
		// REQUEST
		// =====================================================

		if (request == null) {
			throw new IllegalArgumentException(
					"request is required"
			);
		}


		// =====================================================
		// BASE FILTER
		// =====================================================

		String safeDiv =
				normalizeDiv(
						request.div()
				);


		validateExportDate(
				request.expD()
		);


		String safeProcGrp =
				normalizeProcessGroup(
						request.procGrp()
				);


		String safeClassify =
				normalizeClassify(
						request.classify()
				);


		// =====================================================
		// PAGINATION
		// =====================================================

		int safePage =
				normalizePage(
						request.page()
				);


		int safeSize =
				normalizeSize(
						request.size()
				);


		// =====================================================
		// EXCEL FILTER
		// =====================================================

		List<FacConfirmFilterItem> safeFilters =
				normalizeFilters(
						request.filters()
				);


		String safeLogicOperator =
				normalizeLogicOperator(
						request.logicOperator()
				);


		// =====================================================
		// COUNT
		// =====================================================

		long total =
				repository.countSearch(
						safeDiv,
						request.expD(),
						safeProcGrp,
						safeClassify,
						safeFilters,
						safeLogicOperator
				);


		// =====================================================
		// DATA
		// =====================================================

		List<FacConfirmDto> content =
				repository.search(
						safeDiv,
						request.expD(),
						safeProcGrp,
						safeClassify,
						safePage,
						safeSize,
						safeFilters,
						safeLogicOperator
				);


		// =====================================================
		// RESPONSE
		// =====================================================

		return PageResponse.of(
				content,
				safePage,
				safeSize,
				total
		);
	}


	// =========================================================
	// FILTER OPTIONS
	// =========================================================

	@Transactional(readOnly = true)
	public List<String> getFilterOptions(
			FacConfirmFilterOptionsRequest request
	) {

		// =====================================================
		// REQUEST
		// =====================================================

		if (request == null) {
			throw new IllegalArgumentException(
					"request is required"
			);
		}


		// =====================================================
		// FIELD
		// =====================================================

		String field =
				normalizeFilterField(
						request.field()
				);


		// =====================================================
		// BASE FILTERS
		// =====================================================

		String safeDiv =
				normalizeDiv(
						request.div()
				);


		validateExportDate(
				request.expD()
		);


		String safeProcGrp =
				normalizeProcessGroup(
						request.procGrp()
				);

		String safeClassify =
				normalizeClassify(
						request.classify()
				);

		// =====================================================
		// ACTIVE FILTERS
		// =====================================================

		List<FacConfirmFilterItem> safeFilters =
				normalizeFilters(
						request.filters()
				);


		// =====================================================
		// SEARCH TEXT
		// =====================================================

		String safeSearch =
				request.search() == null
						? ""
						: request.search().trim();


		// =====================================================
		// DATA
		// =====================================================

		return repository.findFilterOptions(
				field,
				safeSearch,
				safeDiv,
				request.expD(),
				safeProcGrp,
				safeClassify,
				safeFilters
		);
	}


	// =========================================================
	// PROCESS GROUP SUMMARY
	// =========================================================

	@Transactional(readOnly = true)
	public List<FacConfirmProcessGroupDto> getProcessGroups(
			String div,
			LocalDate expD
	) {

		String safeDiv =
				normalizeDiv(
						div
				);


		validateExportDate(
				expD
		);


		return repository.findProcessGroups(
				safeDiv,
				expD
		);
	}


	// =========================================================
	// NORMALIZE DIV
	// =========================================================

	private String normalizeDiv(
			String div
	) {

		if (
				div == null
						|| div.isBlank()
		) {

			throw new IllegalArgumentException(
					"div is required"
			);
		}


		return div.trim();
	}


	// =========================================================
	// VALIDATE DATE
	// =========================================================

	private void validateExportDate(
			LocalDate expD
	) {

		if (expD == null) {

			throw new IllegalArgumentException(
					"expD is required"
			);
		}
	}


	// =========================================================
	// NORMALIZE PROCESS GROUP
	// =========================================================

	private String normalizeProcessGroup(
			String value
	) {

		if (
				value == null
						|| value.isBlank()
		) {

			throw new IllegalArgumentException(
					"procGrp is required"
			);
		}


		String input =
				value.trim();


		return VALID_PROCESS_GROUPS
				.stream()

				.filter(
						item ->
								item.equalsIgnoreCase(
										input
								)
				)

				.findFirst()

				.orElseThrow(
						() ->
								new IllegalArgumentException(
										"Invalid procGrp: "
												+ value
												+ ". Allowed values: Fine, Heat, Rough"
								)
				);
	}


	// =========================================================
	// NORMALIZE PAGE
	// =========================================================

	private int normalizePage(
			Integer page
	) {

		return Math.max(
				page == null
						? 0
						: page,
				0
		);
	}


	// =========================================================
	// NORMALIZE SIZE
	// =========================================================

	private int normalizeSize(
			Integer size
	) {

		int value =
				size == null
						? DEFAULT_PAGE_SIZE
						: size;


		return Math.min(
				Math.max(
						value,
						1
				),
				MAX_PAGE_SIZE
		);
	}


	// =========================================================
	// NORMALIZE LOGIC OPERATOR
	// =========================================================

	private String normalizeLogicOperator(
			String value
	) {

		if (
				value != null
						&& value.equalsIgnoreCase(
						"or"
				)
		) {

			return "or";
		}


		return "and";
	}


	// =========================================================
	// NORMALIZE FILTER FIELD
	// =========================================================

	private String normalizeFilterField(
			String field
	) {

		if (
				field == null
						|| field.isBlank()
		) {

			throw new IllegalArgumentException(
					"field is required"
			);
		}


		String safeField =
				field.trim();


		if (
				!FacConfirmFilterField.supports(
						safeField
				)
		) {

			throw new IllegalArgumentException(
					"Unsupported Fac Confirm filter field: "
							+ safeField
			);
		}


		return safeField;
	}


	// =========================================================
	// NORMALIZE FILTERS
	// =========================================================

	private List<FacConfirmFilterItem> normalizeFilters(
			List<FacConfirmFilterItem> filters
	) {

		if (
				filters == null
						|| filters.isEmpty()
		) {

			return List.of();
		}


		if (
				filters.size()
						> MAX_FILTERS
		) {

			throw new IllegalArgumentException(
					"Too many filters. Maximum allowed: "
							+ MAX_FILTERS
			);
		}


		for (
				FacConfirmFilterItem filter
				: filters
		) {

			if (filter == null) {
				throw new IllegalArgumentException(
						"Filter item cannot be null"
				);
			}


			String field =
					filter.field();


			if (
					field == null
							|| field.isBlank()
			) {

				throw new IllegalArgumentException(
						"Filter field is required"
				);
			}


			if (
					!FacConfirmFilterField.supports(
							field.trim()
					)
			) {

				throw new IllegalArgumentException(
						"Unsupported Fac Confirm filter field: "
								+ field
				);
			}


			String operator =
					filter.operator();


			if (
					operator == null
							|| operator.isBlank()
			) {

				throw new IllegalArgumentException(
						"Filter operator is required for field: "
								+ field
				);
			}
		}


		return List.copyOf(
				filters
		);
	}
}