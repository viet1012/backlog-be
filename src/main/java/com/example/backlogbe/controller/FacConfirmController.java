package com.example.backlogbe.controller;

import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.dto.facconfirm.*;
import com.example.backlogbe.service.ClientMachineService;
import com.example.backlogbe.service.FacConfirmProcessTimeService;
import com.example.backlogbe.service.FacConfirmService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;


@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/fac-confirm")
@RequiredArgsConstructor
public class FacConfirmController {

	private final FacConfirmService service;

	private final FacConfirmProcessTimeService
			processTimeService;

	private final ClientMachineService clientMachineService;

	// =========================================================
	// DETAIL
	// =========================================================

	@GetMapping
	public PageResponse<FacConfirmDto> getFacConfirm(
			@RequestParam String div,

			@RequestParam
			@DateTimeFormat(
					iso = DateTimeFormat.ISO.DATE
			)
			LocalDate expD,

			@RequestParam String procGrp,

			@RequestParam(
					required = false
			)
			String classify,

			@RequestParam(
					defaultValue = "0"
			)
			int page,

			@RequestParam(
					defaultValue = "100"
			)
			int size
	) {

		return service.getFacConfirm(
				div,
				expD,
				procGrp,
				classify,
				page,
				size
		);
	}

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
	// =========================================================
	// PROCESS GROUPS
	// =========================================================

	@GetMapping("/process-groups")
	public ResponseEntity<
			List<FacConfirmProcessGroupDto>
			> getProcessGroups(

			@RequestParam
			String div,

			@RequestParam
			@DateTimeFormat(
					iso = DateTimeFormat.ISO.DATE
			)
			LocalDate expD

	) {

		return ResponseEntity.ok(
				service.getProcessGroups(
						div,
						expD
				)
		);
	}


	// =========================================================
	// SEARCH
	// =========================================================

	@PostMapping("/search")
	public ResponseEntity<
			PageResponse<FacConfirmDto>
			> search(

			@RequestBody
			FacConfirmSearchRequest request

	) {

		return ResponseEntity.ok(
				service.search(request)
		);
	}


	// =========================================================
	// FILTER OPTIONS
	// =========================================================

	@PostMapping("/filter-options")
	public ResponseEntity<List<String>> getFilterOptions(

			@RequestBody
			FacConfirmFilterOptionsRequest request

	) {

		return ResponseEntity.ok(
				service.getFilterOptions(
						request
				)
		);
	}


	// =========================================================
	// CONFIRMED PROCESSES
	// =========================================================
	@PostMapping("/confirmed-processes")
	public ResponseEntity<List<Map<String, Object>>> getConfirmedProcesses(
			@RequestBody List<String> aufnrs
	) {

		return ResponseEntity.ok(
				processTimeService.getConfirmedProcesses(
						aufnrs
				)
		);
	}
	// =========================================================
	// SAVE PROCESS TIMES
	// =========================================================

	@PatchMapping("/process-times")
	public ResponseEntity<Map<String, Object>> saveProcessTimes(
			@RequestBody FacConfirmProcessTimeRequest request,
			HttpServletRequest httpRequest
	) {

		String clientIp =
				getClientIp(httpRequest);

		String machineName =
				clientMachineService.resolveMachineName(
						clientIp
				);

		int updated =
				processTimeService.save(
						request,
						machineName
				);

		return ResponseEntity.ok(
				Map.of(
						"updated", updated,
						"clientIp", clientIp,
						"machineName", machineName
				)
		);
	}


	// =========================================================
	// GET CLIENT IP
	// =========================================================

	private String getClientIp(
			HttpServletRequest request
	) {

		String forwardedFor =
				request.getHeader(
						"X-Forwarded-For"
				);

		if (isValidHeader(forwardedFor)) {

			return forwardedFor
					.split(",")[0]
					.trim();
		}


		String realIp =
				request.getHeader(
						"X-Real-IP"
				);

		if (isValidHeader(realIp)) {
			return realIp.trim();
		}


		return request.getRemoteAddr();
	}


	// =========================================================
	// VALID IP HEADER
	// =========================================================

	private boolean isValidHeader(
			String value
	) {

		return value != null
				&& !value.isBlank()
				&& !"unknown".equalsIgnoreCase(
				value
		);
	}


	// =========================================================
	// RESOLVE MACHINE NAME
	// =========================================================

	private String resolveMachineName(
			String clientIp
	) {

		if (
				clientIp == null
						|| clientIp.isBlank()
		) {

			return "UNKNOWN";
		}


		try {

			InetAddress address =
					InetAddress.getByName(
							clientIp
					);


			// =============================================
			// LOCAL DEVELOPMENT
			// =============================================

			if (address.isLoopbackAddress()) {

				String localHost =
						InetAddress
								.getLocalHost()
								.getHostName();

				if (
						localHost != null
								&& !localHost.isBlank()
				) {

					return localHost;
				}

				return clientIp;
			}


			// =============================================
			// REMOTE CLIENT
			// Reverse DNS
			// =============================================

			String hostName =
					address.getCanonicalHostName();


			// DNS không tìm được hostname
			// thì Java thường trả lại IP
			if (
					hostName == null
							|| hostName.isBlank()
							|| hostName.equals(clientIp)
			) {

				return clientIp;
			}


			// Nếu domain:
			// PC001.company.local
			//
			// chỉ lấy:
			// PC001
			int dotIndex =
					hostName.indexOf('.');

			if (dotIndex > 0) {
				hostName =
						hostName.substring(
								0,
								dotIndex
						);
			}


			return hostName;


		} catch (Exception e) {

			return clientIp;
		}
	}
}