# OpenCode for Android 📱

**OpenCode'u Android telefonunda yerel bir uygulama gibi kullan.**

Bu depo, Termux içinde çalışan `opencode serve` sunucusunun web arayüzünü
tam ekran gösteren **sarmalayıcı (wrapper) bir Android uygulaması** içerir.
Uygulama tamamen telefonun içinde, Termux ile derlenir — Android Studio'ya
gerek yoktur.

> ⚠️ Bu, OpenCode'un **resmî olmayan** bir topluluk uygulamasıdır.

## Nasıl çalışır?

```
┌─────────────────────────────┐             ┌───────────────────────────────┐
│  OpenCode (APK)             │   HTTP      │  Termux                       │
│                             │ ⇄ http://  │                               │
│  WebView (tam ekran web     │  127.0.0.1 │  opencode serve --port 4096   │
│  arayüzü)                   │   :4097     │  (arka plan servisi)          │
│      ⇅                      │             └───────────────▲───────────────┘
│  yerel kimlik doğrulama     │─── Authorization: Basic ─────┘
│  proxy'si (tüm isteklere    │     başlığı eklenir
│  şifreyi otomatik ekler)    │
└─────────────────────────────┘
```

- Trafik telefonun **loopback adresi** üzerinden akar; hiçbir veri internete gönderilmez.
- Sunucu zaten çalışıyorsa uygulama doğrudan açılır.
- Sunucu kapalıysa uygulamadaki **“Sunucuyu Termux’ta başlat ▶”** düğmesi,
  Termux’un `RUN_COMMAND` arayüzü ile sunucuyu ayağa kaldırır ve uygulama
  otomatik olarak bağlanmayı dener.

### Sunucu şifresi (otomatik giriş)

`opencode serve` normalde her açılışta rastgele bir şifre üretir. Uygulamanın
şifre sormadan bağlanabilmesi için `serve-android.sh` sunucuyu **sabit bir
şifreyle** başlatır:

| Alan | Değer |
|------|-------|
| Kullanıcı adı | `opencode` |
| Varsayılan şifre | `opencode-local` |

Uygulama bu bilgileri nasıl kullanır? WebView'in HTTP Basic desteği sayfanın
kendi `fetch`/XHR isteklerini kapsamadığı için, uygulama **127.0.0.1 üzerinde
küçük bir yerel proxy** (tercihen port 4097) açar: WebView bu proxy'ye
bağlanır, proxy her isteğe `Authorization: Basic …` başlığı ekleyip 4096'daki
sunucuya iletir. Böylece arayüzün TÜM trafiği — API çağrıları, dosya
yüklemeleri ve `/api/event` SSE akışı dahil — şeffaf biçimde kimlik
doğrulanır ve OpenCode'un kendi giriş ekranı hiç görünmez.

Sunucuyu elle farklı bir şifreyle başlattıysan uygulama şifre ekranını
gösterir; **“Sunucu şifresini gir”** düğmesiyle şifreyi
güncelleyebilirsin (uygulama şifreyi cihazda saklar ve proxy yeni şifreyle
devam eder).

Kendi şifreni sabitlemek için Termux’ta:

```bash
OPENCODE_PASSWORD=benim-şifrem opencode serve --hostname 127.0.0.1 --port 4096
```

…ve uygulamada aynı şifreyi gir.

## Kurulum

1. [Releases](../../releases) sayfasından `OpenCode.apk` dosyasını indir.
2. Dosyaya dokun; Android “bilinmeyen kaynaklardan uygulama yükleme” izni
   isterse Termux veya tarayıcıya bu izni ver.
3. Telefonunda **Termux** ve Termux içinde **OpenCode** kurulu olsun:
   ```bash
   curl -fsSL https://opencode.ai/install | bash
   ```

### Otomatik başlatmayı etkinleştir (tek seferlik)

Uygulamanın “Sunucuyu Termux’ta başlat” düğmesinin çalışması için:

```bash
# 1) Başlatma betiğini kur
mkdir -p ~/.opencode
cp scripts/serve-android.sh ~/.opencode/serve-android.sh
chmod +x ~/.opencode/serve-android.sh

# 2) Termux'un harici uygulamalardan komut almasına izin ver
mkdir -p ~/.termux
grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null \
  || echo 'allow-external-apps = true' >> ~/.termux/termux.properties
```

Ayarın etkili olması için Termux’u bir kez tamamen kapatıp yeniden aç.

## Derleme (Termux içinde)

```bash
pkg install -y openjdk-17 aapt d8 apksigner python
pip install pillow   # ikonları yeniden üretmek istersen

git clone https://github.com/matrixmortalx/opencode-android.git
cd opencode-android

python3 scripts/make-icons.py   # launcher ikonlarını üret
bash build.sh                   # OpenCode.apk'yı üret
```

`build.sh` şu adımları yapar:

| Adım | Araç |
|------|------|
| Kaynak derleme (`R.java` üretimi) | `aapt` |
| Java derleme | `javac` (OpenJDK) |
| DEX’e çevirme | `d8` |
| Paketleme + hizalama | `aapt` + `scripts/zipalign.py` |
| İmzalama | `apksigner` (RSA debug anahtarı) |

`android.jar` (Android 35 platform kütüphanesi) ilk derlemede otomatik indirilir.

## Sorun giderme

- **“Sunucuya ulaşılamadı”** → Termux’u aç, `opencode serve` komutunu çalıştır
  ve uygulamaya geri dön (yenileme düğmesi ↻ sağ altta).
- **“Başlat” düğmesi çalışmıyor** → `allow-external-apps = true` ayarının
  yapıldığından ve Termux’un yeniden başlatıldığından emin ol.
- Sunucuyu elle başlatmak istersen:
  `bash ~/.opencode/serve-android.sh`
- WebView içinde hata ayıklamak için Chrome’da `chrome://inspect` aç.

## Yapı

```
├── AndroidManifest.xml              # Uygulama tanımı
├── src/ai/opencode/android/
│   └── MainActivity.java             # Tek activity'lik uygulama
├── res/                              # İkonlar, dizeler, ağ güvenliği ayarı
├── scripts/
│   ├── make-icons.py                 # Launcher ikonu üretici (Pillow)
│   ├── serve-android.sh              # Termux tarafı sunucu başlatıcı
│   └── zipalign.py                   # Termux için minimal zipalign
└── build.sh                          # Derleme süreci
```

## Lisans

MIT — bkz. [LICENSE](LICENSE).
