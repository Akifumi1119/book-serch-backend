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
        int idx = page * size + 1;
        String xml;
        try {
            xml = restClient.get()
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
        } catch (Exception e) {
            throw new NdlApiException("NDL APIへの接続に失敗しました", e);
        }

        if (xml == null || xml.isBlank()) return emptyResult(page, size);

        List<BookResponse> items = parseItems(xml);
        int ndlTotal = parseTotalResults(xml);
        // isBook フィルタで除外された分を totalResults から差し引く
        int filtered = countItems(xml) - items.size();
        int adjustedTotal = Math.max(0, ndlTotal - filtered);

        return BookSearchResponse.builder()
                .items(items)
                .totalResults(adjustedTotal)
                .page(page)
                .size(size)
                .build();
    }

    public static class NdlApiException extends RuntimeException {
        public NdlApiException(String message, Throwable cause) {
            super(message, cause);
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
                if (!isBook(item)) continue;
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

    private int countItems(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return doc.getElementsByTagName("item").getLength();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String extractIsbn(Element item) {
        NodeList identifiers = item.getElementsByTagNameNS("*", "identifier");
        String isbn10 = "";
        String isbn13 = "";
        for (int i = 0; i < identifiers.getLength(); i++) {
            org.w3c.dom.Node node = identifiers.item(i);
            String type = getTypeAttribute(node);
            if (!type.contains("ISBN")) continue;
            String digits = node.getTextContent().trim().replaceAll("[^0-9X]", "");
            if (type.contains("ISBN13") || digits.matches("97[89]\\d{10}")) {
                if (isbn13.isEmpty()) isbn13 = digits;
            } else if (digits.matches("\\d{9}[0-9X]")) {
                if (isbn10.isEmpty()) isbn10 = digits;
            }
        }
        return !isbn13.isEmpty() ? isbn13 : isbn10;
    }

    private boolean isBook(Element item) {
        NodeList categories = item.getElementsByTagName("category");
        for (int i = 0; i < categories.getLength(); i++) {
            if ("図書".equals(categories.item(i).getTextContent().trim())) return true;
        }
        return false;
    }

    private String getTypeAttribute(org.w3c.dom.Node node) {
        org.w3c.dom.NamedNodeMap attrs = node.getAttributes();
        if (attrs == null) return "";
        for (int i = 0; i < attrs.getLength(); i++) {
            if ("type".equals(attrs.item(i).getLocalName())) {
                return attrs.item(i).getNodeValue();
            }
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
