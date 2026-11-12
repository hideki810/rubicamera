#!/usr/bin/env bash
# ルビカメラAPKビルドスクリプト（Gradleを使わない手組み方式）
# 使い方: android/ ディレクトリの親（リポジトリ直下）を起点に、このスクリプトを実行する。
# 前提: JDK 17 と Android SDK（platforms;android-35 / build-tools;35.0.0）が使えること。
set -euo pipefail
cd "$(dirname "$0")"

BT="$ANDROID_HOME/build-tools/35.0.0"
AJAR="$ANDROID_HOME/platforms/android-35/android.jar"

rm -rf build
mkdir -p build/assets build/gen build/classes build/dex build/res

# Webアセット（リポジトリ直下のWeb版一式）を同梱する
cp ../index.html ../imi.html ../live.html ../tsukaikata.html \
   ../common.js ../sw.js ../qr.png \
   ../icon-1-192.png ../icon-1-512.png ../icon-1.svg \
   ../icon-2-192.png ../icon-2-512.png ../icon-2.svg \
   ../icon-3-192.png ../icon-3-512.png ../icon-3.svg \
   ../manifest-1.json ../manifest-2.json ../manifest-3.json \
   build/assets/

# リソース（アイコンはWeb版の icon-1-192.png を流用）
cp -r res/. build/res/
mkdir -p build/res/drawable
cp ../icon-1-192.png build/res/drawable/icon.png

"$BT/aapt2" compile --dir build/res -o build/compiled.zip
"$BT/aapt2" link -o build/app-raw.apk -I "$AJAR" \
  --manifest AndroidManifest.xml --java build/gen -A build/assets build/compiled.zip

javac -encoding UTF-8 -source 17 -target 17 -cp "$AJAR" -d build/classes \
  src/MainActivity.java src/PhotoProvider.java src/CameraActivity.java \
  build/gen/com/hideki810/rubicamera/R.java

"$BT/d8" --lib "$AJAR" --output build/dex $(find build/classes -name '*.class')

cp build/app-raw.apk build/app-unsigned.apk
(cd build/dex && zip -q ../app-unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "Built: android/build/app-aligned.apk (unsigned, aligned)"
