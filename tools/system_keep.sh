#!/bin/sh
# ============================================================
# حماية النظام الشاملة ضد القتل العدواني (system_keep.sh)
# لجميع التطبيقات — وليس تطبيقاً واحداً.
#
# يعالج عرضين:
#   أ) رجوع التطبيقات والمتصفحات لصفحة البداية أثناء التنقل
#      (السبب غالباً: تدمير الأنشطة + قتل الخلفية).
#   ب) قتل التطبيقات الأمامية/المفتوحة بعدوانية.
#
# التشغيل في Termux (مرة واحدة، يبقى دائماً):
#   curl -sL https://raw.githubusercontent.com/starmaen/-wg-auto/main/tools/system_keep.sh -o system_keep.sh
#   chmod +x system_keep.sh
#   su -c "sh $PWD/system_keep.sh"
#
# للتخصيص: عدّل KEEP_EXTRA بالأسماء الإضافية قبل التشغيل (اختياري).
# ملاحظة أمانة: لا سكربت يلغي قاتل الذاكرة تماماً إن امتلأت الرام
# فعلياً — لكن هذا يمنع كل القتل البرمجي/العدواني.
# ============================================================
LOG=/sdcard/system_keep.log
PASS=0
FAIL=0

# حزم إضافية مهمة لك (أضف ما تشاء بمسافة):
KEEP_EXTRA="com.android.chrome org.mozilla.firefox com.brave.browser com.opera.browser com.opera.mini.native com.microsoft.emmx org.telegram.messenger com.whatsapp com.facebook.orca"

ok() { PASS=$((PASS+1)); echo "  ✔ $1"; echo "✔ $1" >> "$LOG"; }
no() { FAIL=$((FAIL+1)); echo "  ✘ $1"; echo "✘ $1" >> "$LOG"; }
have() { command -v "$1" >/dev/null 2>&1 || [ -x "$1" ]; }

if [ "$(id -u)" -ne 0 ]; then
    echo "بالروت فقط: su -c \"sh \$PWD/system_keep.sh\""
    exit 1
fi

echo "حماية النظام الشاملة ... $(date)" | tee "$LOG"
echo ""

# ---------- 1) منع تدمير الأنشطة (السبب الأول للرجوع للبداية) ----------
settings put global always_finish_activities 0 2>/dev/null
[ "$(settings get global always_finish_activities 2>/dev/null)" = "0" ] \
    && ok "منع تدمير الأنشطة عند التنقل" || no "تدمير الأنشطة"

# ---------- 2) توسيع العمليات المخزنة (يُبقي التطبيقات المفتوحة حية) ----------
settings put global activity_manager_constants "max_cached_processes=64" 2>/dev/null
sleep 1
settings get global activity_manager_constants 2>/dev/null | grep -q "max_cached_processes" \
    && ok "العمليات المخزنة: 64" || echo "  ؟ حد العمليات (غير مدعوم في جهازك — طبيعي)"

# ---------- 3) إطفاء البطارية التكيفية ----------
settings put global adaptive_battery_management_enabled 0 2>/dev/null
[ "$(settings get global adaptive_battery_management_enabled 2>/dev/null)" = "0" ] \
    && ok "إطفاء البطارية التكيفية" || echo "  ؟ البطارية التكيفية (غير موجودة — طبيعي)"

# ---------- 4) جمع كل حزم المستخدم (تطبيقاتك كلها، بلا استثناء) ----------
PKGS="$(pm list packages -3 2>/dev/null | sed 's/^package://' | sort -u)"
for extra in $KEEP_EXTRA; do
    echo "$PKGS" | grep -qx "$extra" || PKGS="$PKGS
$extra"
done
COUNT=$(echo "$PKGS" | grep -c .)
echo "عدد الحزم المشمولة: $COUNT"
echo ""

# ---------- 5) تبييض الكل من Doze ----------
W=0
for p in $PKGS; do
    cmd deviceidle whitelist +"$p" >/dev/null 2>&1 && W=$((W+1))
done
[ "$W" -gt 0 ] && ok "بيضاء Doze: $W حزمة" || no "بيضاء Doze"

# ---------- 6) السماح بالخلفية + الاستيقاظ للكل ----------
B=0
for p in $PKGS; do
    cmd appops set "$p" RUN_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "$p" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "$p" WAKE_LOCK allow >/dev/null 2>&1
    cmd appops set "$p" AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore >/dev/null 2>&1
    B=$((B+1))
done
[ "$B" -gt 0 ] && ok "صلاحيات الخلفية/الاستيقاظ: $B حزمة" || no "صلاحيات الخلفية"

# ---------- 7) دلو نشط للكل (إعفاء من قيود الانتظار) ----------
S=0
for p in $PKGS; do
    am set-standby-bucket "$p" active >/dev/null 2>&1 && S=$((S+1))
done
[ "$S" -gt 0 ] && ok "دلو نشط: $S حزمة" || no "دلو نشط"

# ---------- 8) تحقق عشوائي ----------
echo ""
echo "---------- تحقق ----------"
for probe in com.android.chrome org.mozilla.firefox com.star.wgauto; do
    echo "$PKGS" | grep -qx "$probe" || continue
    dumpsys deviceidle whitelist 2>/dev/null | grep -q "$probe" \
        && ok "$probe في بيضاء Doze" || no "$probe ليس في البيضاء"
done

echo ""
echo "=============================="
echo "النتيجة: نجح $PASS — تعذّر $FAIL"
echo "التقرير: $LOG"
echo ""
echo "خطوة أخيرة (يدوية، مرة واحدة): أعد تشغيل الهاتف."
echo "تنبيه بطارية: منع القتل للكل يستهلك أكثر — إن أردت التوفير لاحقاً،"
echo "عدّل KEEP_EXTRA لتقليل القائمة وأعد التشغيل."
