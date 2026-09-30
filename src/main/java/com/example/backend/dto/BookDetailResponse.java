package com.example.backend.dto;

import lombok.Builder;

@Builder
public record BookDetailResponse(
        String isbn,
        String title,
        String author,
        String publisher,
        String publishedDate,
        String series,
        String description,
        String link
) {}
