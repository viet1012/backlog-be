package com.example.backlogbe.controller;

import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.dto.backlog.BacklogExcelExportRequest;
import com.example.backlogbe.dto.backlog.BacklogFilterOptionsRequest;
import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import com.example.backlogbe.dto.backlog.BacklogStatusSummaryDto;
import com.example.backlogbe.service.BacklogExcelService;
import com.example.backlogbe.service.BacklogMainService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;

import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.List;


@RestController
@RequestMapping("/api/backlogs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BacklogMainController {

    private static final String EXCEL_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";


    private final BacklogMainService service;

    private final BacklogExcelService excelService;


    // =========================================================
    // SEARCH + FILTER + SORT
    // =========================================================

    @PostMapping("/search")
    public PageResponse<BacklogMainDto> search(

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size,

            @RequestParam(
                    required = false,
                    defaultValue = ""
            )
            String search,

            @RequestParam(required = false)
            String sort,

            @RequestBody(required = false)
            BacklogFilterRequest filter
    ) {

        return service.getAll(
                page,
                size,
                search,
                filter,
                sort
        );
    }


    // =========================================================
    // EXCEL FILTER OPTIONS
    // =========================================================

    @PostMapping("/filter-options")
    public List<String> getFilterOptions(
            @RequestBody BacklogFilterOptionsRequest request
    ) {

        return service.getFilterOptions(
                request
        );
    }


    // =========================================================
    // STATUS SUMMARY
    // =========================================================

    @PostMapping("/summary/status")
    public BacklogStatusSummaryDto getStatusSummary(
            @RequestBody(required = false)
            BacklogFilterRequest request
    ) {

        BacklogFilterRequest safeRequest =
                request != null
                        ? request
                        : new BacklogFilterRequest(
                                List.of(),
                                "and"
                        );


        return service.getStatusSummary(
                safeRequest
        );
    }


    // =========================================================
    // EXPORT EXCEL
    //
    // POST /api/backlogs/export/excel
    // =========================================================

    @PostMapping(
            value = "/export/excel",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = EXCEL_CONTENT_TYPE
    )
    public ResponseEntity<StreamingResponseBody> exportExcel(

            @RequestBody(required = false)
            BacklogExcelExportRequest request
    ) {

        // =====================================================
        // VALIDATE BEFORE STREAM STARTS
        // =====================================================

        try {

            excelService.validateExportRequest(
                    request
            );

        } catch (IllegalArgumentException e) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    e.getMessage(),
                    e
            );
        }


        // =====================================================
        // FILE NAME
        // =====================================================

        String timestamp =
                LocalDateTime.now()
                        .format(
                                DateTimeFormatter.ofPattern(
                                        "yyyyMMdd_HHmmss"
                                )
                        );


        String fileName =
                "backlog_"
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


        // =====================================================
        // STREAMING RESPONSE
        //
        // IMPORTANT:
        // call excelService.export(...) INSIDE callback.
        // =====================================================

        StreamingResponseBody body =
                outputStream ->
                        excelService.export(
                                outputStream,
                                request
                        );


        // =====================================================
        // RESPONSE
        // =====================================================

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
}