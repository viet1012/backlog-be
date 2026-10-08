package com.example.backlogbe.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.backlogbe.dto.facconfirm.FacConfirmExcelExportRequest;
import com.example.backlogbe.repository.facconfirm.FacConfirmRepository;

class FacConfirmExcelServiceTest {

	private final FacConfirmRepository repository =
			mock(FacConfirmRepository.class);

	private final FacConfirmExcelService service =
			new FacConfirmExcelService(
					repository,
					new FacConfirmService(repository)
			);


	@Test
	void acceptsDataGridFieldNamesFromExportPayload() {

		FacConfirmExcelExportRequest request =
				requestWithColumns(
						List.of(
								"ferth",
								"productGrp",
								"aufnr",
								"zglobalCode",
								"issueD"
						)
				);


		assertDoesNotThrow(
				() -> service.validateExportRequest(request)
		);
	}


	@Test
	void acceptsEveryFacConfirmDataGridField() {

		FacConfirmExcelExportRequest request =
				requestWithColumns(
						List.of(
								"ferth", "productGrp", "aufnr",
								"zglobalCode", "pname", "issueD",
								"exportD", "cusId", "shipBy", "mtoId",
								"prtAddcmt2", "currentProcess", "finalQty",
								"waitingDays", "hasHeatProcess", "isDC53",
								"isTD", "isMolypden", "note", "toDrill",
								"toHeat", "heatStart", "heatFinish", "toPk"
						)
				);


		assertDoesNotThrow(
				() -> service.validateExportRequest(request)
		);
	}


	@Test
	void stillRejectsUnknownColumns() {

		FacConfirmExcelExportRequest request =
				requestWithColumns(
						List.of("notAColumn")
				);


		assertThrows(
				IllegalArgumentException.class,
				() -> service.validateExportRequest(request)
		);
	}


	@Test
	void validatesBaseParametersLikeSearch() {

		FacConfirmExcelExportRequest badProcGrp =
				new FacConfirmExcelExportRequest(
						"PR", LocalDate.of(2026, 9, 19), "Foo",
						null, "All", "", List.of(), "and", List.of("aufnr")
				);

		FacConfirmExcelExportRequest badClassify =
				new FacConfirmExcelExportRequest(
						"PR", LocalDate.of(2026, 9, 19), "Fine",
						"Foo", "All", "", List.of(), "and", List.of("aufnr")
				);

		assertTrue(
				assertThrows(
						IllegalArgumentException.class,
						() -> service.validateExportRequest(badProcGrp)
				).getMessage().startsWith("Invalid procGrp")
		);

		assertThrows(
				IllegalArgumentException.class,
				() -> service.validateExportRequest(badClassify)
		);
	}


	@Test
	void rejectsTooManyRowsBeforeWritingFile() {

		when(repository.countSearch(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn((long) FacConfirmExcelService.MAX_EXPORT_ROWS + 1);

		IllegalArgumentException ex =
				assertThrows(
						IllegalArgumentException.class,
						() -> service.validateExportRequest(
								requestWithColumns(List.of("aufnr"))
						)
				);

		assertTrue(ex.getMessage().contains("vượt giới hạn"));
	}


	private FacConfirmExcelExportRequest requestWithColumns(
			List<String> columns
	) {

		return new FacConfirmExcelExportRequest(
				"PR",
				LocalDate.of(2026, 9, 19),
				"Fine",
				null,
				"All",
				"",
				List.of(),
				"and",
				columns
		);
	}
}
