package com.example.backlogbe.controller;

import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.dto.backlog.BacklogFilterOptionsRequest;
import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import com.example.backlogbe.service.BacklogMainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/backlogs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BacklogMainController {

    private final BacklogMainService service;


    // =========================================================
    // SEARCH + FILTER + SORT
    // =========================================================

    @PostMapping("/search")
    public PageResponse<BacklogMainDto> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestBody(required = false) BacklogFilterRequest filter
    ) {

        return service.getAll(
                page,
                size,
                filter,
                sort
        );
    }


    // =========================================================
    // EXCEL FILTER OPTIONS
    //
    // POST /api/backlogs/filter-options
    //
    // Body:
    //
    // {
    //   "field": "Div",
    //   "filters": [
    //     {
    //       "field": "Status",
    //       "operator": "equals",
    //       "value": "Doing"
    //     }
    //   ],
    //   "logicOperator": "and",
    //   "search": "",
    //   "limit": 100
    // }
    //
    // =========================================================

    @PostMapping("/filter-options")
    public List<String> getFilterOptions(
            @RequestBody BacklogFilterOptionsRequest request
    ) {

        return service.getFilterOptions(
                request
        );
    }
}