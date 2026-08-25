package com.example.backlogbe.service;

import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import com.example.backlogbe.dto.PageResponse;
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
    // GET FILTERED BACKLOG
    // =========================================================

    @Transactional(readOnly = true)
    public PageResponse<BacklogMainDto> getAll(
            int page,
            int size,
            BacklogFilterRequest filter
    ) {

        // =========================
        // SAFE PAGINATION
        // =========================

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


        // =========================
        // SAFE FILTER REQUEST
        // =========================

        BacklogFilterRequest safeFilter =
                filter == null
                        ? new BacklogFilterRequest(
                        List.of(),
                        "and"
                )
                        : filter;


        // =========================
        // COUNT
        // =========================

        long total =
                repository.countFiltered(
                        safeFilter
                );


        // =========================
        // DATA
        // =========================

        List<BacklogMainDto> content =
                repository.findFiltered(
                        safePage,
                        safeSize,
                        safeFilter
                );


        // =========================
        // RESPONSE
        // =========================

        return PageResponse.of(
                content,
                safePage,
                safeSize,
                total
        );
    }
}