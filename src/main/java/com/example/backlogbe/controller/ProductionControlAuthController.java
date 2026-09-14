package com.example.backlogbe.controller;

import com.example.backlogbe.dto.auth.ProductionControlLoginRequest;
import com.example.backlogbe.dto.auth.ProductionControlLoginResponse;
import com.example.backlogbe.dto.auth.ProductionControlRegisterRequest;
import com.example.backlogbe.dto.auth.ProductionControlRegisterResponse;
import com.example.backlogbe.service.ClientMachineService;
import com.example.backlogbe.service.auth.ProductionControlAuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/production-control/auth")
@RequiredArgsConstructor
public class ProductionControlAuthController {

	private final ProductionControlAuthService authService;

	private final ClientMachineService clientMachineService;

	// =========================================================
	// REGISTER
	// =========================================================
	@PostMapping("/register")
	public ResponseEntity<ProductionControlRegisterResponse> register(
			@RequestBody ProductionControlRegisterRequest request,
			HttpServletRequest httpRequest
	) {

		String clientIp =
				clientMachineService.getClientIp(
						httpRequest
				);

		String clientId =
				clientMachineService.resolveMachineName(
						clientIp
				);

		return ResponseEntity.ok(
				authService.register(
						request,
						clientId
				)
		);
	}


	// =========================================================
	// LOGIN
	// =========================================================

	@PostMapping("/login")
	public ResponseEntity<ProductionControlLoginResponse> login(
			@RequestBody ProductionControlLoginRequest request
	) {

		return ResponseEntity.ok(
				authService.login(
						request
				)
		);
	}
}