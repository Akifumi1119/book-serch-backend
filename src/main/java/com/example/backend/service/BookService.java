package com.example.backend.service;

import com.example.backend.dto.BookResponse;
import com.example.backend.dto.BookSearchResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class BookService {

    private static final String NDL_API_BASE = "https://ndlsearch.ndl.go.jp/api/opensearch";

    private final RestClient restClient;

    public BookService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = RestClient.builder()
                .baseUrl(NDL_API_BASE)
                .requestFactory(factory)
                .build();
    }

    public Optional<BookResponse> findByIsbn(String isbn) {
        try {
            String xml = restClient.get()
                    .uri(uri -> uri.queryParam("isbn", isbn).build())
                    .retrieve()
                    .body(String.class);

            if (xml == null || xml.isBlank()) return Optional.empty();

            return parseItems(xml).stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public BookSearchResponse searchBooks(String title, String creator, String publisher, String keyword, int page, int size) {
        try {
            int idx = page * size + 1;
            String xml = restClient.get()
                    .uri(uri -> {
                        org.springframework.web.util.UriBuilder b = uri;
                        if (title != null && !title.isBlank())         b = b.queryParam("title", title);
                        if (creator != null && !creator.isBlank())     b = b.queryParam("creator", creator);
                        if (publisher != null && !publisher.isBlank()) b = b.queryParam("publisher", publisher);
                        if (keyword != null && !keyword.isBlank())     b = b.queryParam("any", keyword);
                        return b.queryParam("cnt", size).queryParam("idx", idx).build();
                    })
                    .retrieve()
                    .body(String.class);

            if (xml == null || xml.isBlank()) return emptyResult(page, size);

            return BookSearchResponse.builder()
                    .items(parseItems(xml))
                    .totalResults(parseTotalResults(xml))
                    .page(page)
                    .size(size)
                    .build();
        } catch (Exception e) {
            return emptyResult(page, size);
        }
    }

    private BookSearchResponse emptyResult(int page, int size) {
        return BookSearchResponse.builder()
                .items(List.of())
                .totalResults(0)
                .page(page)
                .size(size)
                .build();
    }

    private List<BookResponse> parseItems(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder documentBuilder = factory.newDocumentBuilder();
            Document doc = documentBuilder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList items = doc.getElementsByTagName("item");
            if (items.getLength() == 0) return List.of();

            List<BookResponse> results = new java.util.ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                results.add(BookResponse.builder()
                        .isbn(extractIsbn(item))
                        .title(getLocalText(item, "title"))
                        .author(buildAuthor(item))
                        .publisher(getLocalText(item, "publisher"))
                        .publishedDate(getLocalText(item, "date"))
                        .link(getLocalText(item, "link"))
                        .build());
            }
            return results;
        } catch (Exception e) {
            return List.of();
        }
    }

    private int parseTotalResults(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = doc.getElementsByTagNameNS("*", "totalResults");
            if (nodes.getLength() > 0) {
                return Integer.parseInt(nodes.item(0).getTextContent().trim());
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static final java.util.regex.Pattern ISBN_PATTERN =
            java.util.regex.Pattern.compile("97[89]\\d{10}");

    private String extractIsbn(Element item) {
        NodeList identifiers = item.getElementsByTagNameNS("*", "identifier");
        for (int i = 0; i < identifiers.getLength(); i++) {
            String text = identifiers.item(i).getTextContent().trim().replace("-", "");
            java.util.regex.Matcher m = ISBN_PATTERN.matcher(text);
            if (m.find()) return m.group();
        }
        return "";
    }

    private String buildAuthor(Element item) {
        String raw = getLocalText(item, "author");
        if (raw.isEmpty()) return "";

        List<String> withRole = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> s.matches(".*(著|訳|編|監修|監訳).*"))
                .collect(Collectors.toList());

        return withRole.isEmpty() ? raw : String.join("・", withRole);
    }

    private String getLocalText(Element element, String localName) {
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        nodes = element.getElementsByTagName(localName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }
}
