package com.example.backlogbe.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.dto.facconfirm.FacConfirmDto;
import com.example.backlogbe.dto.facconfirm.FacConfirmExcelExportRequest;
import com.example.backlogbe.dto.facconfirm.FacConfirmFilterOptionsRequest;
import com.example.backlogbe.dto.facconfirm.FacConfirmProcessGroupDto;
import com.example.backlogbe.dto.facconfirm.FacConfirmProcessGroupRequest;
import com.example.backlogbe.dto.facconfirm.FacConfirmProcessTimeRequest;
import com.example.backlogbe.dto.facconfirm.FacConfirmSearchRequest;
import com.example.backlogbe.service.ClientMachineService;
import com.example.backlogbe.service.FacConfirmExcelService;
import com.example.backlogbe.service.FacConfirmProcessTimeService;
import com.example.backlogbe.service.FacConfirmService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;


@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/fac-confirm")
@RequiredArgsConstructor
public class FacConfirmController {

	private final FacConfirmService service;

	private final FacConfirmProcessTimeService
			processTimeService;

	private final ClientMachineService clientMachineService;

	private final FacConfirmExcelService excelService;


	private static final String EXCEL_CONTENT_TYPE =
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

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
					defaultValue = "All"
			)
			String heatType,

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
				heatType,
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


	@PostMapping("/process-groups")
	public ResponseEntity<
			List<FacConfirmProcessGroupDto>
			> getProcessGroups(

			@RequestBody
			FacConfirmProcessGroupRequest request

	) {

		return ResponseEntity.ok(
				service.getProcessGroups(
						request
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
	// EXPORT EXCEL
	//
	// POST /api/fac-confirm/export/excel
	// =========================================================

	@PostMapping(
        value = "/export/excel",
        consumes = MediaType.APPLICATION_JSON_VALUE
)
public ResponseEntity<StreamingResponseBody> exportExcel(

        @RequestBody(required = false)
        FacConfirmExcelExportRequest request
) {

    excelService.validateExportRequest(
            request
    );


    String timestamp =
            LocalDateTime.now()
                    .format(
                            DateTimeFormatter.ofPattern(
                                    "yyyyMMdd_HHmmss"
                            )
                    );


    String fileName =
            "fac_confirm_"
                    + timestamp
                    + ".xlsx";


    String encodedFileName =
            URLEncoder.encode(
                            fileName,
                            StandardCharsets.UTF_8
                    )
                    .replace(
                            "+",
                            "%20"
                    );


    StreamingResponseBody body =
            outputStream ->
                    excelService.export(
                            outputStream,
                            request
                    );


    return ResponseEntity
            .ok()

            .header(
                    HttpHeaders.CONTENT_DISPOSITION,

                    "attachment; filename=\""
                            + fileName
                            + "\"; filename*=UTF-8''"
                            + encodedFileName
            )

            .header(
                    HttpHeaders.CACHE_CONTROL,
                    "no-store, no-cache, must-revalidate"
            )

            .contentType(
                    MediaType.parseMediaType(
                            EXCEL_CONTENT_TYPE
                    )
            )

            .body(
                    body
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
}
