package com.example.backlogbe.service;

import com.example.backlogbe.dto.BacklogMainDto;
import com.example.backlogbe.dto.PageResponse;
import com.example.backlogbe.repository.BacklogMainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BacklogMainService {

    private final BacklogMainRepository repository;

    @Transactional(readOnly = true)
    public PageResponse<BacklogMainDto> getAll(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);

        long total = repository.countAll();
        List<BacklogMainDto> content = repository.findAll(safePage, safeSize);

        return PageResponse.of(content, safePage, safeSize, total);
    }
}
