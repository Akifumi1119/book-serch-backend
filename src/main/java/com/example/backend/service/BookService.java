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

/**
 * NDL API は Atom/RSS 形式の XML を返すため、DOM パーサーで各要素を抽出する
 */
@Service
public class BookService {

    /** NDL OpenSearch API のベースURL */
    private static final String NDL_API_BASE = "https://ndlsearch.ndl.go.jp/api/opensearch";

    private final RestClient restClient;

    public BookService() {
        // タイムアウトを設定してハングを防ぐ（接続5秒、読み取り10秒）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = RestClient.builder()
                .baseUrl(NDL_API_BASE)
                .requestFactory(factory)
                .build();
    }

    /**
     * ISBNで書籍を1件検索する
     * NDL APIにISBNをクエリパラメータとして渡し、最初にヒットした書籍を返却
     * 通信エラーや解析エラーは Optional.empty() として扱う
     */
    public Optional<BookResponse> findByIsbn(String isbn) {
        try {
            String xml = restClient.get()
                    .uri(uri -> uri.queryParam("isbn", isbn).build())
                    .retrieve()
                    .body(String.class);

            if (xml == null || xml.isBlank()) return Optional.empty();

            // XMLを解析して書籍リストに変換し、先頭要素を返す
            return parseItems(xml).stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 複数の検索条件で書籍を検索し、ページング結果を返す。
     *
     * NDL APIの仕様:
     *   - cnt: 1ページあたりの取得件数
     *   - idx: 取得開始レコード番号（1始まり）
     *   - 返却XMLに含まれる totalResults はフィルタ前の件数のため、
     *     「図書」以外を除外した分だけ補正して返す。
     */
    public BookSearchResponse searchBooks(String title, String creator, String publisher, String keyword, int page, int size) {
        // NDL APIの idx は1始まりなのでページ番号から計算する
        int idx = page * size + 1;
        String xml;
        try {
            xml = restClient.get()
                    .uri(uri -> {
                        org.springframework.web.util.UriBuilder b = uri;
                        // 値が指定されているパラメータのみクエリに追加する
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

        // NDL APIが返す totalResults には「図書」以外の資料種別も含まれる。
        // 当ページでフィルタにより除外された件数を差し引いて件数を補正する。
        int filtered = countItems(xml) - items.size();
        int adjustedTotal = Math.max(0, ndlTotal - filtered);

        return BookSearchResponse.builder()
                .items(items)
                .totalResults(adjustedTotal)
                .page(page)
                .size(size)
                .build();
    }

    /** NDL API 呼び出し失敗を表す例外 */
    public static class NdlApiException extends RuntimeException {
        public NdlApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 検索結果が0件のときに返す空のレスポンスオブジェクトを生成する */
    private BookSearchResponse emptyResult(int page, int size) {
        return BookSearchResponse.builder()
                .items(List.of())
                .totalResults(0)
                .page(page)
                .size(size)
                .build();
    }

    /**
     * NDL API が返すXMLを解析し、「図書」に該当するアイテムのみを BookResponse リストに変換する。
     * ネームスペースを意識した getElementsByTagNameNS で要素を取得する。
     */
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
                // category が「図書」でない資料種別（雑誌・電子資料等）を除外する
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

    /**
     * NDL APIレスポンスXMLから総件数（opensearch:totalResults）を取得する。
     * 取得できない場合は0を返す。
     */
    private int parseTotalResults(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            // ネームスペースを問わず totalResults 要素を取得する
            NodeList nodes = doc.getElementsByTagNameNS("*", "totalResults");
            if (nodes.getLength() > 0) {
                return Integer.parseInt(nodes.item(0).getTextContent().trim());
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * XMLレスポンスに含まれる item 要素の総数を返す（フィルタ前）。
     * isBook フィルタによる除外件数を計算するために使用する。
     */
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

    /**
     * item 要素からISBNを抽出する。
     * dc:identifier に ISBN10・ISBN13 が複数含まれる場合は ISBN13 を優先して返す。
     * type 属性またはISBN桁数で種別を判定する。
     */
    private String extractIsbn(Element item) {
        NodeList identifiers = item.getElementsByTagNameNS("*", "identifier");
        String isbn10 = "";
        String isbn13 = "";
        for (int i = 0; i < identifiers.getLength(); i++) {
            org.w3c.dom.Node node = identifiers.item(i);
            String type = getTypeAttribute(node);
            if (!type.contains("ISBN")) continue;
            // ハイフンや空白を除いて数字のみにする
            String digits = node.getTextContent().trim().replaceAll("[^0-9X]", "");
            // type属性またはプレフィックス（978/979）でISBN13と判定
            if (type.contains("ISBN13") || digits.matches("97[89]\\d{10}")) {
                if (isbn13.isEmpty()) isbn13 = digits;
            } else if (digits.matches("\\d{9}[0-9X]")) {
                if (isbn10.isEmpty()) isbn10 = digits;
            }
        }
        // ISBN13 が取れた場合は優先、なければ ISBN10 を返す
        return !isbn13.isEmpty() ? isbn13 : isbn10;
    }

    /**
     * item の category 要素に「図書」が含まれているかを確認する。
     * 雑誌・電子資料などは除外するために使用する。
     */
    private boolean isBook(Element item) {
        NodeList categories = item.getElementsByTagName("category");
        for (int i = 0; i < categories.getLength(); i++) {
            if ("図書".equals(categories.item(i).getTextContent().trim())) return true;
        }
        return false;
    }

    /**
     * 指定ノードの属性マップから type 属性の値を返す。
     * ネームスペース付き属性にも対応するため getLocalName() で比較する。
     */
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

    /**
     * dc:author フィールドから著者名を整形して返す。
     * カンマ区切りで複数名が入る場合、役割語（著・訳・編・監修・監訳）を持つ名前のみを抽出し
     * 「・」で結合する。役割語が一つもない場合は生の文字列をそのまま返す。
     */
    private String buildAuthor(Element item) {
        String raw = getLocalText(item, "author");
        if (raw.isEmpty()) return "";

        List<String> withRole = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> s.matches(".*(著|訳|編|監修|監訳).*"))
                .collect(Collectors.toList());

        return withRole.isEmpty() ? raw : String.join("・", withRole);
    }

    /**
     * 指定したローカル名の要素テキストを返す。
     * まずネームスペースを考慮した検索を試み、見つからなければ非修飾名で再検索する。
     */
    private String getLocalText(Element element, String localName) {
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        // ネームスペースなし要素へのフォールバック
        nodes = element.getElementsByTagName(localName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }
}
