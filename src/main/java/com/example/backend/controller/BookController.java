package com.example.backend.controller;

import com.example.backend.dto.BookResponse;
import com.example.backend.dto.BookSearchResponse;
import com.example.backend.service.BookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping("/isbn/{isbn}")
    public ResponseEntity<BookResponse> findByIsbn(@PathVariable String isbn) {
        return bookService.findByIsbn(isbn)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public ResponseEntity<BookSearchResponse> searchBooks(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String creator,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (allBlank(title, creator, publisher, keyword)) {
            return ResponseEntity.badRequest().build();
        }

        int clampedSize = Math.min(Math.max(size, 1), 100);
        BookSearchResponse result = bookService.searchBooks(title, creator, publisher, keyword, page, clampedSize);
        return result.totalResults() == 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    private boolean allBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return false;
        }
        return true;
    }
}
