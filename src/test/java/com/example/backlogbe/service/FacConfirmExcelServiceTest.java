package com.example.backlogbe.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.backlogbe.dto.facconfirm.FacConfirmExcelExportRequest;
import com.example.backlogbe.repository.facconfirm.FacConfirmRepository;

class FacConfirmExcelServiceTest {

	private final FacConfirmExcelService service =
			new FacConfirmExcelService(
					mock(FacConfirmRepository.class)
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
