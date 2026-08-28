package com.example.backlogbe.service;

import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.dto.facconfirm.FacConfirmDto;
import com.example.backlogbe.dto.facconfirm.FacConfirmProcessGroupDto;
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

	private static final Set<String> VALID_PROCESS_GROUPS =
			Set.of(
					"Fine",
					"Heat",
					"Rough"
			);


	private final FacConfirmRepository repository;


	// =========================================================
	// DETAIL
	// =========================================================

	@Transactional(readOnly = true)
	public PageResponse<FacConfirmDto> getFacConfirm(
			String div,
			LocalDate expD,
			String procGrp,
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


		// ================================================
		// SAFE PAGINATION
		// ================================================

		int safePage =
				Math.max(
						page,
						0
				);

		int safeSize =
				Math.min(
						Math.max(
								size,
								1
						),
						200
				);


		// ================================================
		// COUNT
		// ================================================

		long total =
				repository.count(
						safeDiv,
						expD,
						safeProcGrp
				);


		// ================================================
		// DATA
		// ================================================

		List<FacConfirmDto> content =
				repository.findPage(
						safeDiv,
						expD,
						safeProcGrp,
						safePage,
						safeSize
				);


		// ================================================
		// RESPONSE
		// ================================================

		return PageResponse.of(
				content,
				safePage,
				safeSize,
				total
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
	// VALIDATE DIV
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
	// VALIDATE PROCESS GROUP
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
}