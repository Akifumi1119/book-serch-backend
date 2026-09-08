package com.example.backend.dto;

import lombok.Builder;

@Builder
public record BookResponse(
        String isbn,
        String title,
        String author,
        String publisher,
        String publishedDate,
        String link
) {}
