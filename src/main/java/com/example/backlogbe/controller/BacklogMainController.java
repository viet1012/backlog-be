package com.example.backlogbe.controller;

import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.service.BacklogMainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backlogs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BacklogMainController {

    private final BacklogMainService service;


    // =========================================================
    // GENERIC FILTER API
    // =========================================================

    @PostMapping("/search")
    public PageResponse<BacklogMainDto> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestBody BacklogFilterRequest filter
    ) {

        return service.getAll(
                page,
                size,
                filter
        );
    }
}