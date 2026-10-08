package com.example.backlogbe.controller;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.backlogbe.service.ClientMachineService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

// =========================================================
// FAC CONFIRM DEBUG
//
// Chỉ bật ở profile "dev" (--spring.profiles.active=dev).
// Profile khác (production) không đăng ký controller này
// nên /api/fac-confirm/debug-client trả 404.
// =========================================================
@Profile("dev")
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/fac-confirm")
@RequiredArgsConstructor
public class FacConfirmDebugController {

	private final ClientMachineService clientMachineService;

	@GetMapping("/debug-client")
	public Map<String, Object> debugClient(
			HttpServletRequest request
	) {

		String clientIp =
				clientMachineService.getClientIp(
						request
				);

		return Map.of(
				"remoteAddr",
				String.valueOf(request.getRemoteAddr()),

				"xForwardedFor",
				String.valueOf(
						request.getHeader("X-Forwarded-For")
				),

				"xRealIp",
				String.valueOf(
						request.getHeader("X-Real-IP")
				),

				"resolvedClientIp",
				clientIp,

				"machineName",
				clientMachineService.resolveMachineName(
						clientIp
				)
		);
	}
}
