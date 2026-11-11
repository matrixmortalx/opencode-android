#!/data/data/com.termux/files/usr/bin/bash
#
# OpenCode for Android — sunucu başlatma betiği
#
# OpenCode uygulamasındaki "Sunucuyu Termux'ta başlat" düğmesi bu betiği
# çalıştırır. Kurulum:
#
#   mkdir -p ~/.opencode
#   cp scripts/serve-android.sh ~/.opencode/serve-android.sh
#   chmod +x ~/.opencode/serve-android.sh
#
set -u

HOST=127.0.0.1
PORT=4096

# Uygulamanın otomatik kullandığı sabit sunucu şifresi. Sunucu yalnızca
# loopback'e (127.0.0.1) açık olduğu için bu şifre yereldir. Değiştirirsen
# uygulamada da "Sunucu şifresini gir" ile aynı değeri kullan.
OPENCODE_PASSWORD="${OPENCODE_PASSWORD:-opencode-local}"
export OPENCODE_PASSWORD

# Ekran kapanınca Android'in Termux'u uyutmasını engellemeye çalış.
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 || true

# Sunucu zaten çalışıyorsa ve şifre tutuyorsa dokunma.
if command -v curl >/dev/null 2>&1 \
   && curl -sf -o /dev/null --max-time 2 -u "opencode:${OPENCODE_PASSWORD}" \
        "http://${HOST}:${PORT}/api/info"; then
  echo "✔ OpenCode sunucusu zaten çalışıyor: http://${HOST}:${PORT}"
  sleep 2
  exit 0
fi

# opencode ikili dosyasını bul.
OC="$(command -v opencode || true)"
[ -z "$OC" ] && OC="$HOME/.local/bin/opencode"
if [ ! -x "$OC" ]; then
  echo "✖ OpenCode bulunamadı! Önce kur:"
  echo "    curl -fsSL https://opencode.ai/install | bash"
  sleep 5
  exit 1
fi

echo "▶ OpenCode sunucusu başlatılıyor: http://${HOST}:${PORT}"
echo "  Uygulamaya geri dönebilirsin; sunucu Termux'ta çalışmaya devam eder."
exec "$OC" serve --hostname "$HOST" --port "$PORT"
