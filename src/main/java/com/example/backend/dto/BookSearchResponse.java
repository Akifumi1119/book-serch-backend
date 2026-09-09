package com.example.backend.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record BookSearchResponse(
        List<BookResponse> items,
        int totalResults,
        int page,
        int size
) {}
