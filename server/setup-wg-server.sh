#!/bin/bash
# ============================================================
#  WG Auto — سيرفر WireGuard خاص بك بأمر واحد (Ubuntu/Debian)
#  الاستخدام:  sudo bash setup-wg-server.sh [عدد_العملاء] [المنفذ]
#  مثال:       sudo bash setup-wg-server.sh 5 443
#
#  لمن؟ صديق خارج البلد (عنده إنترنت منزلي أو أي VPS بأوبونتو + IP عام).
#  أنت لا تحتاج بطاقة — من يشغّل السيرفر فقط يحتاج جهازاً وإنترنت.
# ============================================================
set -e

CLIENTS="${1:-5}"
PORT="${2:-51820}"
WG_NET="10.7.0"
WG_DIR="/etc/wireguard"
OUT_DIR="$HOME/wg-clients"

if [ "$EUID" -ne 0 ]; then
  echo "شغّل بصلاحيات الجذر: sudo bash $0"
  exit 1
fi

echo "==> تثبيت WireGuard..."
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq wireguard curl qrencode iptables > /dev/null

echo "==> تفعيل تمرير الحزم..."
echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wgauto.conf
sysctl -q -p /etc/sysctl.d/99-wgauto.conf

PUB_IF=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $5; exit}')
if [ -z "$PUB_IF" ]; then
  echo "تعذّر تحديد واجهة الإنترنت — تحقق من الاتصال"
  exit 1
fi
echo "==> واجهة الإنترنت: $PUB_IF"

umask 077
mkdir -p "$WG_DIR" "$OUT_DIR"

SERVER_PRIV=$(wg genkey)
SERVER_PUB=$(echo "$SERVER_PRIV" | wg pubkey)

# ملف السيرفر
{
  echo "[Interface]"
  echo "PrivateKey = $SERVER_PRIV"
  echo "Address = $WG_NET.1/24"
  echo "ListenPort = $PORT"
  echo "PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -t nat -A POSTROUTING -o $PUB_IF -j MASQUERADE"
  echo "PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -t nat -D POSTROUTING -o $PUB_IF -j MASQUERADE"
} > "$WG_DIR/wg0.conf"

# عنوان السيرفر العام (للوصول من الخارج)
EXT_IP=$(curl -s4 --max-time 10 ifconfig.me || curl -s4 --max-time 10 api.ipify.org || echo "SERVER_IP")
echo "==> عنوان السيرفر العام: $EXT_IP"

i=2
n=1
while [ "$n" -le "$CLIENTS" ]; do
  C_PRIV=$(wg genkey)
  C_PUB=$(echo "$C_PRIV" | wg pubkey)
  {
    echo ""
    echo "# client$n"
    echo "[Peer]"
    echo "PublicKey = $C_PUB"
    echo "AllowedIPs = $WG_NET.$i/32"
  } >> "$WG_DIR/wg0.conf"

  cat > "$OUT_DIR/client$n.conf" <<EOF
[Interface]
PrivateKey = $C_PRIV
Address = $WG_NET.$i/32
DNS = 1.1.1.1, 1.0.0.1
MTU = 1280

[Peer]
PublicKey = $SERVER_PUB
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = $EXT_IP:$PORT
PersistentKeepalive = 25
EOF
  chmod 600 "$OUT_DIR/client$n.conf"
  echo "----- QR client$n (امسحه ضوئياً أو أرسل الملف) -----"
  qrencode -t ansiutf8 < "$OUT_DIR/client$n.conf" || true
  i=$((i + 1))
  n=$((n + 1))
done

echo "==> تشغيل السيرفر..."
systemctl enable --now wg-quick@wg0 2>/dev/null || wg-quick up wg0

# جدار الحماية (إن وجد ufw)
if command -v ufw >/dev/null 2>&1; then
  ufw allow "$PORT"/udp >/dev/null 2>&1 || true
  echo "==> فُتح المنفذ $PORT/udp في ufw"
fi

echo ""
echo "============================================================"
echo " تم! ملفات العملاء في: $OUT_DIR"
echo " أرسل كل ملف لجهاز مختلف (تليجرام/واتساب) ثم استورده في WG Auto."
echo " المنفذ: $PORT/udp — إن حُجب جرّب 443 ثم 123."
echo " تحذير: احتفظ بنسخة من $WG_DIR/wg0.conf ولا تشارك PrivateKey السيرفر."
echo "============================================================"
