package com.example.backlogbe.exception;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.backlogbe.controller.FacConfirmController;
import com.example.backlogbe.service.ClientMachineService;
import com.example.backlogbe.service.FacConfirmExcelService;
import com.example.backlogbe.service.FacConfirmProcessTimeService;
import com.example.backlogbe.service.FacConfirmService;

class FacConfirmExceptionHandlerTest {

	private final FacConfirmService service =
			mock(FacConfirmService.class);

	private final FacConfirmProcessTimeService processTimeService =
			mock(FacConfirmProcessTimeService.class);

	private final MockMvc mockMvc =
			MockMvcBuilders
					.standaloneSetup(
							new FacConfirmController(
									service,
									processTimeService,
									mock(ClientMachineService.class),
									mock(FacConfirmExcelService.class)
							)
					)
					.setControllerAdvice(
							new GlobalExceptionHandler(),
							new FacConfirmExceptionHandler()
					)
					.build();


	@Test
	void editPermissionErrorReturns400WithMessage() throws Exception {

		when(processTimeService.save(any(), any()))
				.thenThrow(new IllegalArgumentException(
						"Field not editable in Rough: 123456 (To Heat)"
				));

		mockMvc.perform(
						patch("/api/fac-confirm/process-times")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"employeeId\":\"E1\",\"changes\":[],\"procGrp\":\"Rough\"}")
				)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value(
						"Field not editable in Rough: 123456 (To Heat)"
				));
	}


	@Test
	void sqlErrorReturns500WithoutSqlOrStackTrace() throws Exception {

		when(service.search(any()))
				.thenThrow(new BadSqlGrammarException(
						"search",
						"SELECT * FROM F2_Backlog_Main",
						new SQLException("Invalid column name 'Secret'")
				));

		mockMvc.perform(
						post("/api/fac-confirm/search")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{}")
				)
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(content().string(not(containsString("SELECT"))))
				.andExpect(content().string(not(containsString("Secret"))))
				.andExpect(content().string(not(containsString("Exception"))));
	}


	@Test
	void internalIllegalStateReturns500NotGlobal403() throws Exception {

		when(service.search(any()))
				.thenThrow(new IllegalStateException(
						"Missing DataGrid field mapping for export column: X"
				));

		mockMvc.perform(
						post("/api/fac-confirm/search")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{}")
				)
				.andExpect(status().isInternalServerError())
				.andExpect(content().string(not(containsString("DataGrid"))));
	}


	@Test
	void malformedJsonReturns400WithMessage() throws Exception {

		mockMvc.perform(
						post("/api/fac-confirm/search")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{bad json")
				)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Invalid request body"));
	}


	@Test
	void missingRequestParamReturns400WithMessage() throws Exception {

		mockMvc.perform(
						get("/api/fac-confirm")
				)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("div is required"));
	}
}
