# HANDOVER — rubicamera（ルビカメラ）

最終更新: 2026-11-20

## 概要
漢字にふりがなを振るWebアプリ＋Android APK。施設職員向けに配布している。GitHub: hideki810/rubicamera

## 置き場所
- 正本: GitHubリポジトリ `hideki810/rubicamera`（main）
- VM作業用: Muse VM `~/workspace/github/rubicamera-sync`（スナップショット同期フォルダ。git cloneではない点に注意）
- 配布: Googleドライブの `rubicamera.apk`（配布リンクは不変）＋ `rubicamera-version.json`（更新通知用）
- 署名keystore: Muse VM `~/workspace/apk-build/rubicamera.keystore`（パスワードは同フォルダ内のテキストファイル）

## 済んだこと
- v1.0.12 でアプリ内撮影の動作確認済み（2026-11-12）
- GitHub Actions による自動ビルド＋署名のパイプライン完成

## 残っていること・未決
- 職員向けのAPIキー運用方針（未決定）

## 引き継ぎルール
- 同じファイルを複数環境で同時編集しない。作業前に正本（GitHub）の最新を取り、終わったらpushして状態を揃える
- 進捗が変わったらこのファイルを更新する
