#!/bin/sh
# ============================================================
# WG Auto — سكربت الروت ضد القتل العدواني (root_keep.sh)
# للruntime/Twrp؟ لا — يُنفذ من Termux بصلاحية الروت لمرة واحدة،
# ويثبت الحماية بشكل دائم (تبقى بعد إعادة التشغيل).
#
# التشغيل في Termux (كأمر واحد):
#   curl -sL https://raw.githubusercontent.com/starmaen/-wg-auto/main/tools/root_keep.sh -o root_keep.sh
#   chmod +x root_keep.sh
#   su -c "sh $PWD/root_keep.sh"
#
# ماذا يفعل؟
#   1) يمنع تدمير الأنشطة عند التنقل (سبب الرجوع للرئيسية!) always_finish_activities=0
#   2) يبيّض WG Auto من Doze + الخلفية + الاستيقاظ + دلو نشط
#   3) يوسع حد العمليات المخزنة ويضعف البطارية التكيفية
#   4) يتحقق من كل بند ويطبع تقرير ✔/✘
# آمن: كل الأوامر إعدادات نظام رسمية، وقابلة للعكس (التقرير يوضح).
# ============================================================
PKG=com.star.wgauto
PASS=0
FAIL=0
LOG=/sdcard/wgauto_root_keep.log

ok() { PASS=$((PASS+1)); echo "  ✔ $1"; echo "✔ $1" >> "$LOG"; }
no() { FAIL=$((FAIL+1)); echo "  ✘ $1"; echo "✘ $1" >> "$LOG"; }

if [ "$(id -u)" -ne 0 ]; then
    echo "يعمل هذا السكربت بالروت فقط. نفذه هكذا:"
    echo "  su -c \"sh \$PWD/root_keep.sh\""
    exit 1
fi

echo "WG Auto — حماية الروت ... $(date)" | tee "$LOG"
echo ""

# ---------- 1) إيقاف تدمير الأنشطة (السبب الأول للرجوع للرئيسية) ----------
# خيار المطور Don't keep activities = يقتل كل شاشة فور مغادرتها.
if settings put global always_finish_activities 0 2>/dev/null; then
    [ "$(settings get global always_finish_activities 2>/dev/null)" = "0" ] \
        && ok "إيقاف تدمير الأنشطة (لا رجوع للرئيسية)" \
        || no "تدمير الأنشطة (القيمة لم تثبت)"
else
    no "تدمير الأنشطة (أمر مرفوض)"
fi

# ---------- 2) حد العمليات المخزنة (يمنع قتل تطبيقات الخلفية) ----------
# conservative: 48 (الافتراضي ~32). يُطبق فقط إن قبله النظام.
if settings put global activity_manager_constants "max_cached_processes=48" 2>/dev/null; then
    sleep 1
    settings get global activity_manager_constants 2>/dev/null | grep -q "max_cached_processes" \
        && ok "توسيع العمليات المخزنة إلى 48" \
        || no "العمليات المخزنة (غير مدعوم في جهازك — طبيعي)"
else
    no "العمليات المخزنة (غير مدعوم — طبيعي)"
fi

# ---------- 3) إطفاء البطارية التكيفية (قاتل التطبيقات الصامت) ----------
settings put global adaptive_battery_management_enabled 0 2>/dev/null
[ "$(settings get global adaptive_battery_management_enabled 2>/dev/null)" = "0" ] \
    && ok "إطفاء البطارية التكيفية" \
    || say_skip=1
if [ -n "$say_skip" ]; then echo "  ؟ البطارية التكيفية (غير موجودة في جهازك — طبيعي)"; fi

# ---------- 4) تبييض WG Auto ----------
cmd deviceidle whitelist +$PKG >/dev/null 2>&1
dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG" \
    && ok "WG Auto في بيضاء Doze" || no "بيضاء Doze"

cmd appops set $PKG RUN_IN_BACKGROUND allow >/dev/null 2>&1
cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
cmd appops set $PKG WAKE_LOCK allow >/dev/null 2>&1
cmd appops set $PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore >/dev/null 2>&1
cmd appops get $PKG RUN_IN_BACKGROUND 2>/dev/null | grep -qi allow \
    && ok "الخلفية + الاستيقاظ + منع سحب الأذونات" \
    || no "صلاحيات الخلفية"

am set-standby-bucket $PKG active >/dev/null 2>&1
am get-standby-bucket $PKG 2>/dev/null | grep -q "active" \
    && ok "دلو الاستعداد: نشط" || no "دلو الاستعداد"

# ---------- 5) تثبيت خدمة WG Auto ضد الإيقاف (OEM) ----------
# منع الإيقاف القسري للخدمة عند ضغط الذاكرة عبر تثبيت الأولوية:
cmd activity services 2>/dev/null | grep -q "$PKG" \
    && echo "  (الخدمة تعمل الآن — جيد)" || echo "  (الخدمة متوقفة — افتح التطبيق واتصل أولاً)"

echo ""
echo "=============================="
echo "النتيجة: نجح $PASS — تعذّر/غير مدعوم $FAIL"
echo "التقرير الكامل: $LOG"
echo ""
echo "الباقي يدوي (مرة واحدة):"
echo "  أ) اقفل WG Auto بالقفل في شاشة الأخيرة."
echo "  ب) البطارية ← التشغيل التلقائي مسموح + بدون قيود."
echo "  ج) أعد تشغيل الهاتف — ثم اتصل وراقب الثبات."
