package com.example.backend.controller;

import com.example.backend.dto.BookResponse;
import com.example.backend.dto.BookSearchResponse;
import com.example.backend.service.BookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 国立国会図書館（NDL）のOpenSearch APIをバックエンドとして使用する
 */
@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    /**
     * ISBNで書籍を1件取得
     * NDL APIに問い合わせ、ヒットした最初の書籍情報を返す
     * 見つからない場合は 404 Not Found を返す
     */
    @GetMapping("/isbn/{isbn}")
    public ResponseEntity<BookResponse> findByIsbn(@PathVariable String isbn) {
        return bookService.findByIsbn(isbn)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 書籍を条件で検索する（ページング対応）
     * title / creator / publisher / keyword のうち少なくとも1つが必須
     * size は 1〜100 にクランプし、NDL APIの仕様に合わせる
     * NDL APIへの接続失敗時は 503 Service Unavailable を返す
     */
    @GetMapping("/search")
    public ResponseEntity<BookSearchResponse> searchBooks(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String creator,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // 検索条件が全て空の場合はバッドリクエストを返す
        if (allBlank(title, creator, publisher, keyword)) {
            return ResponseEntity.badRequest().build();
        }

        // size を 1〜100 の範囲に収める（NDL API の上限に合わせる）
        int clampedSize = Math.min(Math.max(size, 1), 100);
        try {
            BookSearchResponse result = bookService.searchBooks(title, creator, publisher, keyword, page, clampedSize);
            return result.totalResults() == 0
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok(result);
        } catch (BookService.NdlApiException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /** 全ての文字列がnullまたは空白かどうかを判定する */
    private boolean allBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return false;
        }
        return true;
    }
}
