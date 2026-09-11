# My Portfolio Backend

ポートフォリオサイト用のバックエンドAPI。国立国会図書館（NDL）サーチAPIを利用して書籍情報を提供します。

## 技術スタック

- **Java 26**
- **Spring Boot 4.1.1**
- **Lombok**
- **Docker**
- **Render**（デプロイ先）

## ローカル起動

```bash
./gradlew bootRun
```

起動後、`http://localhost:8080` でアクセスできます。

## ビルド

```bash
./gradlew bootJar
```

## API エンドポイント

### 書籍検索

#### ISBNで取得

```
GET /api/books/isbn/{isbn}
```

| パラメータ | 型 | 説明 |
|---|---|---|
| isbn | string | ISBN-13（例: `9784297138233`） |

**レスポンス**

| ステータス | 説明 |
|---|---|
| 200 | 書籍情報 |
| 404 | 該当書籍なし |

```json
{
  "isbn": "9784297138233",
  "title": "プロを目指す人のためのJava入門",
  "author": "山田祥寛 著",
  "publisher": "技術評論社",
  "publishedDate": "2023",
  "link": "https://ndlsearch.ndl.go.jp/books/R100000002-I032974638"
}
```

---

#### キーワード・著者などで検索

```
GET /api/books/search
```

| パラメータ | 必須 | デフォルト | 説明 |
|---|---|---|---|
| title | いずれか1つ以上 | - | タイトル（部分一致） |
| creator | | - | 著者名（部分一致） |
| publisher | | - | 出版社名（部分一致） |
| keyword | | - | キーワード横断検索 |
| page | | `0` | ページ番号（0始まり） |
| size | | `20` | 1ページの件数（上限100） |

**レスポンス**

| ステータス | 説明 |
|---|---|
| 200 | 書籍リスト |
| 400 | 検索パラメータがすべて空 |
| 404 | 該当書籍なし |
| 503 | NDL APIへの接続失敗 |

```json
{
  "items": [
    {
      "isbn": "9784297130725",
      "title": "Vue 3フロントエンド開発の教科書",
      "author": "齊藤新三 著・山田祥寛 監修",
      "publisher": "技術評論社",
      "publishedDate": "2022",
      "link": "https://ndlsearch.ndl.go.jp/books/R100000002-I032383264"
    }
  ],
  "totalResults": 2,
  "page": 0,
  "size": 20
}
```

> `isbn` フィールドは取得できない場合に空文字列 `""` になります。  
> `totalResults` は図書のみの近似値です。

---

### ヘルスチェック

```
GET /ping
```

```json
{ "status": "ok" }
```

```
GET /actuator/health
```

## CORS

以下のオリジンからのリクエストを許可しています。

- `http://localhost:5173`（開発環境）
- `https://Akifumi1119.github.io`（本番環境）

## デプロイ

Render に Docker コンテナとしてデプロイしています。`render.yaml` に設定が記載されています。

- ヘルスチェック: `/actuator/health`
- プロファイル: `prod`
- プラン: Free（一定時間アクセスがない場合スリープします）※但し、UptimeRobotで5分毎にGET /pingにアクセスしているため実質スリープなし
