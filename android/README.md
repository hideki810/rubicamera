# ルビカメラ Androidアプリ（APK）の自動ビルド

このフォルダは、Web版ルビカメラを Android アプリ（APK）として組み立てるための外側プログラムです。

## 仕組み

- GitHub Actions（`.github/workflows/build-apk.yml`）が、アプリ本体（`index.html` など）や
  このフォルダが更新されるたびに、自動でAPKをビルドします。
- 署名キー（GitHub Secrets: `KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS`）が
  登録されている場合は、署名済みAPK `rubicamera.apk` が成果物（Artifacts）に出ます。
  未登録の場合は未署名APKになります。
- ビルド方法は Gradle を使わない手組み方式です（`build-apk.sh`）。

## バージョンを上げるとき

`AndroidManifest.xml` の `versionCode` と `versionName` を上げてコミットしてください。
あわせて、アプリ内のお知らせ用に `rubicamera-version.json`（Googleドライブ）の
バージョンも同じ値に更新が必要です。

## ワークフローファイルの変更について

`.github/workflows/` のファイルは Muse（GitHub App）からは編集できません。
変更が必要な場合は、リポジトリ所有者が GitHub の Web 画面から直接編集してください。
