#!/bin/sh
# ============================================================
# WG Auto — مثبت البقاء في الخلفية (keepalive.sh)
# يمنح التطبيق كل حصانة ممكنة ضد قاتل البطارية/الذاكرة + يتحقق.
#
# التشغيل في Termux:
#   curl -sL https://raw.githubusercontent.com/starmaen/-wg-auto/main/tools/keepalive.sh -o keepalive.sh
#   chmod +x keepalive.sh
#   ./keepalive.sh
#
# الأوضاع:
#   1) عادي (Termux فقط): فحص + تعليمات يدوية — لا يستطيع منح صلاحيات.
#   2) Shizuku (بدون روت): ثبّت تطبيق Shizuku واربطه بتصحيح لاسلكي، ثم
#      تأكد أن أمر rish يعمل في Termux وأعد تشغيل السكربت — يطبق كل شيء.
#   3) روت: يطبق كل شيء تلقائياً عبر su.
# ============================================================
PKG=com.star.wgauto
PASS=0
FAIL=0

say() { echo "$1"; }
ok() { PASS=$((PASS+1)); echo "  ✔ $1"; }
no() { FAIL=$((FAIL+1)); echo "  ✘ $1"; }

# ---------- كشف الصلاحية ----------
MODE="plain"
if command -v rish >/dev/null 2>&1; then
    MODE="shizuku"
elif su -c true >/dev/null 2>&1; then
    MODE="root"
fi
say "الوضع: $MODE"
say ""

priv() {
    # تنفيذ أمر بصلاحية: يعيد 0 عند النجاح
    if [ "$MODE" = "shizuku" ]; then
        rish -c "$*" >/dev/null 2>&1
    elif [ "$MODE" = "root" ]; then
        su -c "$*" >/dev/null 2>&1
    else
        return 1
    fi
}

if [ "$MODE" = "plain" ]; then
    say "أنت بدون روت وبدون Shizuku: الترمينال العادي ممنوع من تغيير"
    say "إعدادات النظام — لذا سأفحص ما أستطيع وأعطيك الخطوات اليدوية."
    say ""
    say "للحماية الكاملة (مرة واحدة فقط):"
    say "  1) ثبّت تطبيق Shizuku من GitHub/Play."
    say "  2) فعّل خيارات المطور ← تصحيح USB ← تصحيح لاسلكي، واربط Shizuku."
    say "  3) في Termux تأكد أن أمر rish يعمل، ثم أعد: ./keepalive.sh"
    say ""
    # قفل استيقاظ جزئي لتيرموكس نفسه إن توفر (لا يحمي WG Auto مباشرة)
    if command -v termux-wake-lock >/dev/null 2>&1; then
        termux-wake-lock 2>/dev/null && ok "قفل استيقاظ Termux (جزئي)" || no "قفل الاستيقاظ"
    else
        say "  (ثبّت Termux:API لقفل الاستيقاظ — اختياري)"
    fi
    say ""
    say "خطوات يدوية إلزامية الآن من الإعدادات:"
    say "  أ) تبويب VPN في التطبيق ← بطاقة الثبات ← استثناء من البطارية."
    say "  ب) اقفل WG Auto بالقفل في شاشة التطبيقات الأخيرة."
    say "  ج) البطارية ← التشغيل التلقائي مسموح + بدون قيود."
    say ""
    say "انتهى (وضع عادي). للنتيجة الكاملة أعد التشغيل بوضع Shizuku/روت."
    exit 0
fi

say "تطبيق الحماية الكاملة على $PKG ..."
say ""

# 1) القائمة البيضاء لتوفير البطارية (Doze)
if priv "cmd deviceidle whitelist +$PKG"; then ok "قائمة Doze البيضاء"; else no "قائمة Doze البيضاء"; fi

# 2) السماح بالعمل في الخلفية
if priv "cmd appops set $PKG RUN_IN_BACKGROUND allow"; then ok "العمل في الخلفية"; else no "العمل في الخلفية"; fi
if priv "cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow"; then ok "أي عمل في الخلفية"; else no "أي عمل في الخلفية"; fi

# 3) قفل الاستيقاظ + بدء الخدمات الأمامية
if priv "cmd appops set $PKG WAKE_LOCK allow"; then ok "قفل الاستيقاظ"; else no "قفل الاستيقاظ"; fi

# 4) دلو الاستعداد: نشط (يعفي من قيود Doze/App Standby)
if priv "am set-standby-bucket $PKG active"; then ok "دلو الاستعداد: نشط"; else no "دلو الاستعداد"; fi

# 5) إقلاع تلقائي (تأكيد أن المستقبل موجود — يعمل بلا صلاحية)
if priv "cmd appops set $PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore"; then ok "منع سحب الأذونات تلقائياً"; else no "منع سحب الأذونات"; fi

say ""
say "---------- التحقق ----------"
if [ "$MODE" = "shizuku" ]; then
    rish -c "dumpsys deviceidle whitelist" 2>/dev/null | grep -q "$PKG" && ok "مثبت في بيضاء Doze" || no "ليس في بيضاء Doze"
    rish -c "cmd appops get $PKG RUN_IN_BACKGROUND" 2>/dev/null | grep -qi "allow" && ok "الخلفية: allow" || say "  ؟ تعذّر قراءة AppOps (طبيعي في بعض الأجهزة)"
else
    su -c "dumpsys deviceidle whitelist" 2>/dev/null | grep -q "$PKG" && ok "مثبت في بيضاء Doze" || no "ليس في بيضاء Doze"
fi

say ""
say "النتيجة: نجح $PASS — تعذّر $FAIL"
if [ "$FAIL" -eq 0 ]; then
    say "ممتاز: الحماية مطبقة. أعد تشغيل الهاتف مرة واحدة ثم اتصل."
else
    say "أكمل الباقي يدوياً من الإعدادات (البطارية ← بدون قيود + قفل التطبيق)."
fi
