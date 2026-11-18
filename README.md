# MediaDL

URL を貼り付けるだけで、YouTube・X (Twitter)・Instagram・Facebook・TikTok の動画や画像を保存できる、個人利用向けの Android アプリです。
yt-dlp と ffmpeg（[youtubedl-android](https://github.com/JunkFood02/youtubedl-android)）を内蔵しています。

## 主な機能

- **URL 自動判別**: 貼り付け／他アプリの「共有」から受け取った URL のサービスを判別し、バッジで表示
- **画像の一括ダウンロード**: 複数画像の投稿（カルーセル等）はまとめて保存（`_01`, `_02` … の連番）
- **動画の画質選択**（ダウンロード前に想定サイズを表示）
  - 最良: 元ファイルの最大画質（上限なし）
  - 普通: 1080p（フルHD）
  - 低画質: 720p
  - 目標の画質が無い場合は、それ以下で最も近い画質を選び「普通（1080pなし→720p）」のように表示（縦長動画は短辺で判定）
- **保存先の選択**: 設定画面でフォルダを選択（既定は `Download/MediaDL`）。その下にダウンロード日の `DL_YYYYMMDD` フォルダを自動作成
- **空き容量チェック**: ダウンロード後の空きが「1GB または総容量の10% の大きい方」を下回る場合は警告（続行／中止を選択）。空き容量を超える場合は実行不可
- **進捗表示**: パーセント・プログレスバー・キャンセル。フォアグラウンドサービスで動作し、完了時に通知
- **yt-dlp の更新**: 設定画面の「yt-dlpを更新」
- **cookies.txt の取り込み**（任意）: ログインが必要な投稿用
- Material 3 デザイン、ダークテーマ対応

## サービスごとの制限

| サービス | 状況 |
|---|---|
| YouTube | 通常は利用可。仕様変更が多いため、取得できなくなったら「yt-dlpを更新」。最近の yt-dlp は YouTube 用に外部 JS ランタイムを求める場合があり、選べる画質が限られることがあります |
| X (Twitter) | 動画は yt-dlp。画像は vxtwitter の公開 API 経由（ログイン不要）。鍵アカウント・センシティブ設定の投稿は cookies.txt が必要 |
| Instagram | ログインが必要なことが多い（cookies.txt 推奨） |
| Facebook | 公開動画が中心。写真はプレビュー画像（og:image）になる場合あり |
| TikTok | 動画は可。写真スライドはプレビュー画像のみの場合あり |

※ 個人利用の範囲で、各サービスの利用規約と著作権を守ってご利用ください。

## インストール（サイドロード）

1. [Releases](../../releases) から `MediaDL-v<バージョン>.apk` をスマホのブラウザで直接ダウンロード
2. ダウンロード完了の通知（またはファイルアプリ）から APK をタップ
3. 「この提供元のアプリを許可」をオンにしてインストール
4. 初回起動時に通知の許可を求められたら「許可」

- 対応: Android 8.0 (API 26) 以上、arm64-v8a（最近のほぼすべてのスマホ）
- 更新時は同じ署名の APK を上書きインストールしてください

## ビルド

```bash
./gradlew testReleaseUnitTest assembleRelease
```

署名情報はリポジトリに含めていません。次のいずれかで指定します（無い場合は未署名でビルドされます）。

- 環境変数: `MEDIADL_STORE_FILE`, `MEDIADL_STORE_PASSWORD`, `MEDIADL_KEY_ALIAS`, `MEDIADL_KEY_PASSWORD`
- `MEDIADL_KEYSTORE_PROPS` が指す、またはプロジェクト直下の `keystore.properties`（`storeFile`, `storePassword`, `keyAlias`, `keyPassword`）

出力: `app/build/outputs/apk/release/app-arm64-v8a-release.apk`（ABI 別 + ユニバーサル）

## 構成

- Kotlin / Jetpack Compose / Material 3
- minSdk 26 / targetSdk 36
- `core/` URL 判別・画質選択・容量判定・日付フォルダ名（単体テストあり）
- `data/` yt-dlp による解析、保存先（SAF / MediaStore）への書き込み
- `service/` ダウンロード用フォアグラウンドサービス
- `ui/` 画面（メイン・設定）
