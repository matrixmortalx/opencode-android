#!/data/data/com.termux/files/usr/bin/bash
#
# OpenCode for Android — APK derleme betiği (Termux içinde çalışır)
#
# Gereksinimler:
#   pkg install -y openjdk-17 aapt d8 apksigner python
#   (ikonları yeniden üretmek için: pip install pillow)
#
set -euo pipefail

cd "$(dirname "$0")"

ANDROID_JAR=android.jar
if [ ! -f "$ANDROID_JAR" ]; then
  echo ">> android.jar indiriliyor…"
  curl -fL -o "$ANDROID_JAR" \
    "https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar"
fi

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/7] Kaynaklar derleniyor (aapt)…"
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR"

echo "[2/7] Java derleniyor (javac)…"
javac -source 1.8 -target 1.8 -nowarn -bootclasspath "$ANDROID_JAR" \
     -d build/classes \
     $(find src build/gen -name '*.java')

echo "[3/7] DEX oluşturuluyor (d8)…"
d8 --release --lib "$ANDROID_JAR" --min-api 26 --output build/dex \
   $(find build/classes -name '*.class')

echo "[4/7] Paketleniyor (aapt)…"
aapt package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F build/unsigned.apk
( cd build/dex && aapt add ../unsigned.apk classes.dex )

echo "[5/7] Hizalanıyor (zipalign.py)…"
python3 scripts/zipalign.py build/unsigned.apk build/aligned.apk

echo "[6/7] İmza anahtarı kontrol ediliyor…"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore \
    -storepass android -keypass android \
    -alias opencode -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=OpenCode Android, O=OpenCode, C=TR" >/dev/null 2>&1
fi

echo "[7/7] İmzalanıyor (apksigner)…"
apksigner sign --ks debug.keystore --ks-key-alias opencode \
  --ks-pass pass:android --key-pass pass:android \
  --out OpenCode.apk build/aligned.apk

apksigner verify OpenCode.apk
echo
echo "✔ Derleme tamam: $(pwd)/OpenCode.apk"
ls -la OpenCode.apk
