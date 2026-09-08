package com.example.backend.service;

import com.example.backend.dto.BookResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class BookService {

    private static final String NDL_API_BASE = "https://ndlsearch.ndl.go.jp/api/opensearch";

    private final RestClient restClient;

    public BookService() {
        this.restClient = RestClient.builder().baseUrl(NDL_API_BASE).build();
    }

    public Optional<BookResponse> findByIsbn(String isbn) {
        String xml = restClient.get()
                .uri(uri -> uri.queryParam("isbn", isbn).build())
                .retrieve()
                .body(String.class);

        if (xml == null || xml.isBlank()) return Optional.empty();

        return parseFirstItem(xml, isbn);
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
                    .author(getLocalText(item, "author"))
                    .publisher(getLocalText(item, "publisher"))
                    .publishedDate(getLocalText(item, "date"))
                    .link(getLocalText(item, "link"))
                    .build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String getLocalText(Element element, String localName) {
        // 名前空間あり（dc:creator 等）と なし（title, link）の両方に対応
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        // 名前空間なしにフォールバック
        nodes = element.getElementsByTagName(localName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }
}
