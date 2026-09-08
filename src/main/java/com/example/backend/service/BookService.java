package com.example.backend.service;

import com.example.backend.dto.BookResponse;
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

            return parseFirstItem(xml, isbn);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<BookResponse> parseFirstItem(String xml, String isbn) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder documentBuilder = factory.newDocumentBuilder();
            Document doc = documentBuilder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList items = doc.getElementsByTagName("item");
            if (items.getLength() == 0) return Optional.empty();

            Element item = (Element) items.item(0);

            return Optional.of(BookResponse.builder()
                    .isbn(isbn)
                    .title(getLocalText(item, "title"))
                    .author(buildAuthor(item))
                    .publisher(getLocalText(item, "publisher"))
                    .publishedDate(getLocalText(item, "date"))
                    .link(getLocalText(item, "link"))
                    .build());
        } catch (Exception e) {
            return Optional.empty();
        }
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
