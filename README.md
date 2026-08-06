# ルビカメラ

カメラで撮った・かざした文字にふりがなを表示するWebアプリです。3つのモードがあります。

- `www/index.html` … ①高精度版（写真を撮ってOCR＋ふりがな）
- `www/live.html` … ②かざして読む（リアルタイムでルビを重ね表示）
- `www/imi.html` … ③意味しらべ

ブラウザでそのまま開いても使えますし、[Capacitor](https://capacitorjs.com/)でAndroidアプリ（APK）として端末にインストールすることもできます。

## Androidアプリ（APK）を作る

Google Playストアには公開せず、ビルドしたAPKを直接端末にインストールして使う手順です。開発者登録（費用）は不要です。

### 必要なもの

- [Android Studio](https://developer.android.com/studio)（Android SDKが同梱されています）
- Node.js 18以降

### 手順

1. リポジトリを取得し、依存関係をインストール
   ```bash
   npm install
   ```
2. AndroidプロジェクトをAndroid Studioで開く
   ```bash
   npx cap open android
   ```
   （初回はGradleの同期が走ります。Android Studioがなければ `android/` フォルダを直接開いても構いません）
3. Android Studio上部メニューから **Build → Build Bundle(s) / APK(s) → Build APK(s)** を選択
4. ビルドが終わると通知に「locate」リンクが出るので、そこから `app-debug.apk` を取得
   （コマンドラインの場合は `cd android && ./gradlew assembleDebug`。生成物は `android/app/build/outputs/apk/debug/app-debug.apk`）
5. そのAPKファイルをAndroid端末に転送してインストール（初回は「提供元不明のアプリ」の許可が必要です）

`www/`配下のHTMLを直接編集した場合は、Android Studioを開き直すか `npx cap sync android` を実行してからビルドしてください。

### アイコン・スプラッシュ画面の変更

`resources/icon.png`（1024×1024）と `resources/splash.png`（2732×2732）を差し替えて、以下を実行すると再生成されます。

```bash
npx capacitor-assets generate --android
```

### カメラの権限について

`www/live.html` はリアルタイムでカメラ映像を使うため、初回起動時にカメラの利用許可を求めるダイアログが表示されます。「許可」を選んでください（`android/app/src/main/java/.../MainActivity.java` で起動時に権限リクエストしています）。

### 署名付きAPK（配布用）にする場合

開発中はデバッグ用APK（`assembleDebug`）で十分ですが、より広く配布したい場合はAndroid Studioの **Build → Generate Signed Bundle / APK** から自己署名の鍵を作成してビルドしてください（Play Storeに出さない限り追加費用はかかりません）。
