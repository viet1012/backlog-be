package com.example.backlogbe.service;

import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.dto.backlog.BacklogFilterOptionsRequest;
import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import com.example.backlogbe.dto.backlog.BacklogStatusSummaryDto;
import com.example.backlogbe.repository.backlog.BacklogMainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BacklogMainService {

    private final BacklogMainRepository repository;


    // =========================================================
    // SEARCH / FILTER / PAGINATION
    // =========================================================

    @Transactional(readOnly = true)
    public PageResponse<BacklogMainDto> getAll(
            int page,
            int size,
            BacklogFilterRequest filter,
            String sort
    ) {

        int safePage =
                Math.max(
                        page,
                        0
                );

        int safeSize =
                Math.min(
                        Math.max(
                                size,
                                1
                        ),
                        200
                );


        BacklogFilterRequest safeFilter =
                filter == null
                        ? new BacklogFilterRequest(
                        List.of(),
                        "and"
                )
                        : filter;


        long total =
                repository.countFiltered(
                        safeFilter
                );


        List<BacklogMainDto> content =
                repository.findFiltered(
                        safePage,
                        safeSize,
                        safeFilter,
                        sort
                );


        return PageResponse.of(
                content,
                safePage,
                safeSize,
                total
        );
    }


    // =========================================================
    // EXCEL FILTER OPTIONS
    // =========================================================

    @Transactional(readOnly = true)
    public List<String> getFilterOptions(
            BacklogFilterOptionsRequest request
    ) {

        // -----------------------------------------------------
        // VALIDATE
        // -----------------------------------------------------

        if (
                request == null
                        || request.field() == null
                        || request.field().isBlank()
        ) {
            throw new IllegalArgumentException(
                    "Filter field is required"
            );
        }


        // -----------------------------------------------------
        // SAFE SEARCH
        // -----------------------------------------------------

        String search =
                request.search() == null
                        ? ""
                        : request.search().trim();


        // -----------------------------------------------------
        // SAFE LIMIT
        // -----------------------------------------------------

        int limit =
                request.limit() == null
                        ? 100
                        : Math.min(
                        Math.max(
                                request.limit(),
                                1
                        ),
                        500
                );


        // -----------------------------------------------------
        // ACTIVE FILTERS
        // -----------------------------------------------------

        BacklogFilterRequest activeFilters =
                new BacklogFilterRequest(
                        request.filters() == null
                                ? List.of()
                                : request.filters(),

                        request.logicOperator() == null
                                ? "and"
                                : request.logicOperator()
                );


        // -----------------------------------------------------
        // DISTINCT VALUES
        // -----------------------------------------------------

        return repository.findDistinctValues(
                request.field().trim(),
                search,
                limit,
                activeFilters
        );
    }


    @Transactional(readOnly = true)
    public BacklogStatusSummaryDto getStatusSummary(
            BacklogFilterRequest request
    ) {

        return repository.findStatusSummary(
                request
        );
    }
}