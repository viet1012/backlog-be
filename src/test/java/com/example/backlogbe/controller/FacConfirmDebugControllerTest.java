package com.example.backlogbe.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.backlogbe.service.ClientMachineService;

class FacConfirmDebugControllerTest {

	@Nested
	@WebMvcTest(controllers = FacConfirmDebugController.class)
	class WithoutDevProfile {

		@Autowired
		MockMvc mockMvc;

		@MockitoBean
		ClientMachineService clientMachineService;

		@Test
		void debugClientReturns404() throws Exception {
			mockMvc.perform(get("/api/fac-confirm/debug-client"))
					.andExpect(status().isNotFound());
		}
	}


	@Nested
	@ActiveProfiles("dev")
	@WebMvcTest(controllers = FacConfirmDebugController.class)
	class WithDevProfile {

		@Autowired
		MockMvc mockMvc;

		@MockitoBean
		ClientMachineService clientMachineService;

		@Test
		void debugClientReturns200() throws Exception {
			when(clientMachineService.getClientIp(any())).thenReturn("127.0.0.1");
			when(clientMachineService.resolveMachineName(any())).thenReturn("PC-DEV");

			mockMvc.perform(get("/api/fac-confirm/debug-client"))
					.andExpect(status().isOk());
		}
	}
}
