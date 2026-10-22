package com.star.wgauto

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.Message
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

class Actions(
    val toggle: () -> Unit,
    val importFiles: () -> Unit,
    val batteryExempt: () -> Unit,
    val testAll: () -> Unit,
    val testOne: (String) -> Unit,
    val syncService: () -> Unit,
    val restoreSystem: () -> Unit,
    val shutdownAll: () -> Unit,
    val openVpnSettings: () -> Unit,
    val activateConfig: (String) -> Unit,
    val startOvpn: (OvpnServer) -> Unit,
    val startExtraOvpn: (ExtraServer) -> Unit,
    val startExtraXray: (ExtraServer) -> Unit
)

class MainActivity : ComponentActivity() {
    private val app get() = application as WgApp
    private var pendingAfterPerm: (() -> Unit)? = null

    // ---- تبديل تلقائي بين سيرفرات Xray حتى ينجح أحدها ----
    private var xrayWanted = false
    private var xrayTries = 0
    private var xrayTried = ""
    private var xrayOrder: List<String> = emptyList()
    private val xrayHandler = Handler(Looper.getMainLooper())
    private val xrayWatchdog = object : Runnable {
        override fun run() {
            if (xrayWanted && !XrayEngine.state.value.running) {
                toast("تعذّر الاتصال بالسيرفر — تبديل تلقائي")
                nextXray()
            }
        }
    }

    private fun beginXray(s: ExtraServer) {
        val same = setOf("vmess", "vless", "trojan", "ss")
        xrayOrder = listOf(s.id) + app.store.extra.value
            .filter { it.connectable && it.protocol in same && it.id != s.id }
            .sortedWith(compareBy({ if (it.lat < 0) 1 else 0 }, { it.lat }))
            .map { it.id }
        xrayTries = 0
        xrayWanted = true
        startXrayId(s.id)
    }

    private fun startXrayId(id: String) {
        val s = app.store.extra.value.firstOrNull { it.id == id }
        if (s == null) {
            nextXray(); return
        }
        xrayTried = id
        if (app.engine.status.value.running) app.engine.stop()
        if (app.ovpn.isUp()) app.ovpn.stop()
        val err = XrayEngine.start(this, s)
        if (err.isNotBlank()) {
            toast("تعذّر تجهيز Xray: $err")
            nextXray(); return
        }
        toast("جارٍ الاتصال: ${s.title}")
        xrayHandler.removeCallbacks(xrayWatchdog)
        xrayHandler.postDelayed(xrayWatchdog, 15_000)
    }

    private fun nextXray() {
        xrayHandler.removeCallbacks(xrayWatchdog)
        if (!xrayWanted) return
        xrayTries++
        val idx = xrayOrder.indexOf(xrayTried)
        val nxt = if (idx < 0) xrayOrder.firstOrNull() else xrayOrder.drop(idx + 1).firstOrNull()
        if (xrayTries > 5 || nxt == null) {
            xrayWanted = false
            XrayEngine.stop(this)
            toast("لا سيرفر يعمل الآن — حدّث القائمة ثم أعد المحاولة")
            return
        }
        toast("تجربة سيرفر آخر (${xrayTries}/5)…")
        startXrayId(nxt)
    }

    private fun cancelXrayAuto() {
        xrayWanted = false
        xrayHandler.removeCallbacks(xrayWatchdog)
    }

    private val xrayReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            XrayEngine.apply(
                running = i.getBooleanExtra(XrayEngine.EXTRA_RUNNING, false),
                connecting = i.getBooleanExtra(XrayEngine.EXTRA_CONNECTING, false),
                title = i.getStringExtra(XrayEngine.EXTRA_TITLE).orEmpty(),
                error = i.getStringExtra(XrayEngine.EXTRA_ERROR).orEmpty()
            )
            val st = XrayEngine.state.value
            if (st.running) {
                xrayWanted = false
                xrayHandler.removeCallbacks(xrayWatchdog)
            } else if (st.error.isNotBlank() && xrayWanted) {
                xrayHandler.removeCallbacks(xrayWatchdog)
                nextXray()
            }
        }
    }

    private val vpnPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val action = pendingAfterPerm
            pendingAfterPerm = null
            if (res.resultCode == RESULT_OK) action?.invoke() else toast("لم يتم منح إذن VPN")
        }
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            var ok = 0
            var fail = 0
            uris.forEach {
                val (o, f) = importUri(it)
                ok += o
                fail += f
            }
            if (ok > 0 || fail > 0) {
                toast("تم استيراد $ok كونفيج" + (if (fail > 0) " • تعذّر $fail" else "") + " — تُفحص عند تشغيل الـ VPN")
            }
        }

    private val lightScheme = lightColorScheme(
        primary = Color(0xFF1565C0), onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E4FF), onPrimaryContainer = Color(0xFF0B2A5B),
        secondary = Color(0xFF3F6FB5)
    )
    private val darkScheme = darkColorScheme(
        primary = Color(0xFF90CAF9), onPrimary = Color(0xFF0D2B57),
        primaryContainer = Color(0xFF1B3F73), onPrimaryContainer = Color(0xFFD6E4FF),
        secondary = Color(0xFF9DB8E6)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ContextCompat.registerReceiver(
                this, xrayReceiver, IntentFilter(XrayEngine.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val actions = Actions(
            toggle = { onToggle() },
            importFiles = { importLauncher.launch(arrayOf("*/*")) },
            batteryExempt = { batteryExempt() },
            testAll = { app.ovpn.stop(); withVpnPermission { app.engine.testAll() } },
            testOne = { id -> app.ovpn.stop(); withVpnPermission { app.engine.testConfigs(listOf(id)) } },
            syncService = { syncService() },
            restoreSystem = { app.engine.restoreSystemDns() },
            shutdownAll = {
                XrayEngine.stop(this)
                app.engine.shutdownAll {
                    stopService(Intent(this, AutoService::class.java))
                    toast("أُغلق التطبيق بالكامل — لن يعمل شيء حتى تشغّله يدوياً")
                }
            },
            openVpnSettings = {
                try {
                    startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS))
                } catch (e: Exception) {
                    toast("تعذّر فتح إعدادات VPN في هذا الجهاز")
                }
            },
            activateConfig = { id ->
                app.store.updateSettings { it.copy(autoConfig = false, selConfigId = id) }
                app.ovpn.stop()
                cancelXrayAuto()
                XrayEngine.stop(this)
                withVpnPermission {
                    ContextCompat.startForegroundService(this, Intent(this, AutoService::class.java))
                    app.engine.connectManual(id)
                }
            },
            // إذن VPN من النظام (مرة واحدة) ثم تشغيل OpenVPN الداخلي — بدونه يفشل establish بصمت
            startOvpn = { s ->
                withVpnPermission {
                    cancelXrayAuto()
                    XrayEngine.stop(this)
                    val st = app.store
                    // كلمة سر VPNBook تتغير أسبوعياً: جلب طازج قبل كل اتصال يمنع AUTH_FAILED من أول ضغطة
                    if (s.key.startsWith("vbk-")) {
                        toast("جارٍ جلب كلمة سر VPNBook ثم الاتصال…")
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val pwd = VpnBook.fetchPassword() ?: st.prefGet("vpnbookPass")
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (!pwd.isNullOrEmpty()) {
                                    st.prefPut("vpnbookPass", pwd)
                                    toast(startOvpnInternal(app, s, st.settings.value.ovpnProto))
                                } else {
                                    toast("تعذّر جلب كلمة السر — تحقق من الإنترنت وأعد المحاولة")
                                }
                            }
                        }
                    } else {
                        toast(startOvpnInternal(app, s, st.settings.value.ovpnProto))
                    }
                }
            },
            // سيرفر مستورد من اشتراك عام: نفق واحد فقط — نوقف WireGuard ثم نشغّل الكونفيج كما هو
            startExtraOvpn = { s ->
                withVpnPermission {
                    cancelXrayAuto()
                    XrayEngine.stop(this)
                    if (app.engine.status.value.running) app.engine.stop()
                    if (app.ovpn.isUp()) app.ovpn.stop()
                    val server = OvpnServer(
                        key = "extra-" + s.id, host = s.host, ip = s.host,
                        country = s.country, lastSeen = System.currentTimeMillis()
                    )
                    toast(app.ovpn.start(server, s.raw, null, null))
                }
            },
            // نفق Xray: يوقف أي نفق آخر أولاً (نفق واحد فقط)
            startExtraXray = { s -> withVpnPermission { beginXray(s) } }
        )
        syncService()
        if (app.store.settings.value.autoPurgeExpired) {
            val n = app.store.purgeExpiredConfigs()
            if (n > 0) toast("حُذفت $n كونفيجات منتهية الصلاحية تلقائياً — ولّد بديلاً من VPNBook")
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkScheme else lightScheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LicenseGate(app) { Root(app, actions) }
                }
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(xrayReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // فحص خفيف عند فتح التطبيق: لا يُسقط النفق العامل، فقط يحدّث الأرقام المعروضة
        val st = app.engine.status.value
        if (st.running && st.connected) app.engine.healthCheck()
        else if (!st.running && app.store.settings.value.backgroundScan) app.engine.scanNetwork("فتح التطبيق")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun withVpnPermission(action: () -> Unit) {
        val i = VpnService.prepare(this)
        if (i == null) {
            action()
        } else {
            pendingAfterPerm = action
            vpnPermLauncher.launch(i)
        }
    }

    /** الخدمة تعمل إذا كان الـ VPN يعمل أو كان الفحص الخلفي مفعّلاً. */
    private fun syncService() {
        val need = app.engine.status.value.running || app.store.settings.value.backgroundScan
        val i = Intent(this, AutoService::class.java)
        if (need) ContextCompat.startForegroundService(this, i) else stopService(i)
    }

    private fun onToggle() {
        if (app.engine.status.value.running) {
            app.engine.stop()
            if (!app.store.settings.value.backgroundScan) stopService(Intent(this, AutoService::class.java))
        } else {
            if (app.store.configs.value.none { it.enabled }) {
                toast("أضف كونفيجاً واحداً على الأقل وفعّله")
                return
            }
            withVpnPermission { startVpn() }
        }
    }

    private fun startVpn() {
        app.ovpn.stop() // نفق واحد فقط: فصل OpenVPN الداخلي قبل تشغيل WireGuard
        cancelXrayAuto()
        XrayEngine.stop(this)
        ContextCompat.startForegroundService(this, Intent(this, AutoService::class.java))
        app.engine.start()
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: "config"

    /** يستورد ملف .conf أو ملف .zip يحوي عدة كونفيجات. يعيد (نجح, فشل). */
    private fun importUri(uri: Uri): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        try {
            val name = displayName(uri)
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val isZip = name.endsWith(".zip", true) ||
                (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
            if (isZip) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val base = e.name.substringAfterLast('/').substringAfterLast('\\')
                            if (!base.startsWith(".") && !e.name.startsWith("__MACOSX")) {
                                val data = zin.readBytes()
                                if (data.size < 200_000) {
                                    val text = data.toString(Charsets.UTF_8)
                                    if (text.contains("[Interface]", true) && text.contains("[Peer]", true)) {
                                        val n = base.substringBeforeLast('.')
                                        if (app.engine.addConfig(n, text) == null) ok++ else fail++
                                    }
                                }
                            }
                        }
                        zin.closeEntry()
                        e = zin.nextEntry
                    }
                }
            } else {
                val text = bytes.toString(Charsets.UTF_8)
                // تنظيف اسم ملفات المتصفح (.conf.txt المزدوجة): لا حاجة لتغيير الامتداد يدوياً
                var cleanName = name
                fun strip(s: String, suf: String): String =
                    if (s.endsWith(suf, ignoreCase = true)) s.substring(0, s.length - suf.length) else s
                repeat(3) {
                    cleanName = strip(strip(strip(strip(cleanName, ".txt"), ".conf"), ".ovpn"), ".cfg").trim()
                }
                if (app.engine.addConfig(cleanName.ifEmpty { name }, text) == null) ok++ else fail++
            }
        } catch (e: Exception) {
            fail++
        }
        return ok to fail
    }

    private fun batteryExempt() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("التطبيق مستثنى بالفعل من توفير الطاقة")
        } else {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}

// ============================================================ UI

/** بوابة الترخيص: تعرض شاشة تفعيل حتى يكون الجهاز مرخَّصاً، ثم تعرض محتوى التطبيق. */
@Composable
fun LicenseGate(app: WgApp, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val deviceId = remember { License.deviceId(ctx) }
    var status by remember { mutableStateOf(License.status(app.store)) }

    if (status.valid) {
        content()
        return
    }

    var tapCount by remember { mutableIntStateOf(0) }
    var showOwner by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var ownerPhrase by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                    .clickable {
                        tapCount++
                        if (tapCount >= 7) {
                            showOwner = true
                            tapCount = 0
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("WG", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Text("تفعيل WG Auto", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                "أرسل معرّف جهازك أدناه إلى مطوّر التطبيق للحصول على كود التفعيل، ثم الصقه في الحقل تحته.",
                fontSize = 14.sp
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("معرّف جهازك", fontWeight = FontWeight.Bold)
                    Text(License.formatId(deviceId), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("device id", deviceId))
                            msg = "نُسخ معرّف الجهاز"
                        }, modifier = Modifier.weight(1f)) { Text("نسخ") }
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "معرّف جهازي لتفعيل WG Auto: ${License.formatId(deviceId)}")
                            }
                            ctx.startActivity(Intent.createChooser(send, null))
                        }, modifier = Modifier.weight(1f)) { Text("إرسال") }
                    }
                }
            }
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("كود التفعيل") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                when (val r = License.activate(app.store, deviceId, code)) {
                    is License.Attempt.Ok -> { status = License.status(app.store); msg = "" }
                    is License.Attempt.Wrong -> msg = "كود غير صحيح لهذا الجهاز"
                    is License.Attempt.Locked -> msg = "محاولات كثيرة — انتظر ${r.secondsLeft} ثانية"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("تفعيل") }
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)

            if (showOwner) {
                HorizontalDivider()
                Text("دخول المالك", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = ownerPhrase, onValueChange = { ownerPhrase = it },
                    label = { Text("العبارة السرّية") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    when (val r = License.ownerLogin(app.store, ownerPhrase)) {
                        is License.Attempt.Ok -> status = License.status(app.store)
                        is License.Attempt.Wrong -> msg = "عبارة غير صحيحة"
                        is License.Attempt.Locked -> msg = "محاولات كثيرة — انتظر ${r.secondsLeft} ثانية"
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("دخول") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(app: WgApp, a: Actions) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf(
        "🏠 الرئيسية", "📄 الكونفيجات", "🌍 VPN", "🚀 سيرفرات", "🛡️ DNS", "⚙️ الإعدادات"
    )
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // شريط قابل للتمرير: النصوص لا تُقصّ ولا تتكدّس
            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = 6.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                titles.forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(t, maxLines = 1, softWrap = false) }
                    )
                }
            }
            when (tab) {
                0 -> HomeTab(app, a)
                1 -> ConfigsTab(app, a)
                2 -> OvpnTab(app, a)
                3 -> ExtraServersTab(app, a)
                4 -> DnsTab(app)
                else -> SettingsTab(app, a)
            }
        }
    }
}


/** قسم السيرفرات الإضافية: استيراد اشتراكات عامة وتشغيل wg/ovpn منها. */
@Composable
fun ExtraServersTab(app: WgApp, a: Actions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by app.store.extra.collectAsState()
    val sources by app.store.extraSourcesFlow.collectAsState()
    val xr by XrayEngine.state.collectAsState()
    val updatedAt by app.store.extraUpdatedAt.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(app.store.prefGet("extraAuto") != "0") }
    var autoPickOn by remember { mutableStateOf(app.store.prefGet("extraAutoConnect") != "0") }
    var expanded by remember { mutableStateOf(false) }
    // معيار «الأفضل»: يعمل ← فقد أقل ← تذبذب أقل ← زمن أقل
    val rank = compareBy<ExtraServer>(
        { if (it.lat < 0) 1 else 0 }, { it.loss }, { it.jitter },
        { if (it.lat < 0) Int.MAX_VALUE else it.lat }
    )

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    fun setAuto(v: Boolean) {
        auto = v
        app.store.prefPut("extraAuto", if (v) "1" else "0")
    }

    fun addAll(found: List<ExtraServer>) {
        if (found.isEmpty()) return
        app.store.updateExtra { cur ->
            val keys = found.map { it.key }.toSet()
            (found + cur.filter { it.key !in keys }).distinctBy { it.key }.take(800)
        }
        app.store.setExtraUpdatedAt(System.currentTimeMillis())
    }

    fun importText(text: String, src: String) {
        if (text.isBlank()) {
            toast("النص فارغ")
            return
        }
        busy = "جارٍ تحليل المحتوى…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val parsed = ExtraServers.parseSubscription(text, src)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                busy = ""
                if (parsed.isEmpty()) {
                    toast("لا سيرفرات مفهومة — تأكد أنه رابط اشتراك أو كونفيج wg/ovpn")
                } else {
                    addAll(parsed)
                    toast("أُضيف ${parsed.size} سيرفر — اضغط فحص أو اتصال")
                }
            }
        }
    }

    fun testOne(s: ExtraServer) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ms = ExtraServers.tcpTest(s.host, s.port)
            val cc = if (s.country.isBlank()) ExtraServers.resolveCountry(s.host) else s.country
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                app.store.updateExtra { l ->
                    l.map {
                        if (it.id == s.id) it.copy(
                            lat = ms, testedAt = System.currentTimeMillis(),
                            country = it.country.ifBlank { cc }
                        ) else it
                    }
                }
            }
        }
    }

    // العلم مطلوب لكل سيرفر: من الاسم أولاً، وإن غاب نجلبه من عنوان الاتصال بالخلفية (مرة واحدة).
    fun resolveMissingCountries() {
        val pending = list.filter { it.country.isBlank() }.take(20)
        if (pending.isEmpty()) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (s in pending) {
                val cc = ExtraServers.resolveCountry(s.host)
                if (cc.isNotBlank()) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        app.store.updateExtra { l -> l.map { if (it.id == s.id) it.copy(country = cc) else it } }
                    }
                }
            }
        }
    }

    fun connect(s: ExtraServer) {
        when (s.protocol) {
            "wg" -> {
                XrayEngine.stop(ctx)
                val cfg = WgConfig(name = "Extra • " + s.title, text = s.raw)
                app.store.updateConfigs { it + cfg }
                a.activateConfig(cfg.id)
                toast("يُتصل عبر WireGuard: ${s.title}")
            }
            "ovpn" -> {
                XrayEngine.stop(ctx)
                a.startExtraOvpn(s)
            }
            else -> a.startExtraXray(s)
        }
    }

    /** الاختيار التلقائي: يفحص كل السيرفرات القابلة للتشغيل ويتصل بالأسرع استجابة. */
    fun connectFastest() {
        val candidates = list.filter { it.connectable }
        if (candidates.isEmpty()) {
            toast("لا سيرفرات قابلة للتشغيل — أضف اشتراكاً صالحاً")
            return
        }
        if (busy.isNotEmpty()) return
        busy = "جارٍ فحص ${candidates.size} سيرفر لاختيار الأسرع…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val results = candidates.map { s ->
                async { s to ExtraServers.tcpSamples(s.host, s.port) }
            }.map { it.await() }
            val alive = results.filter { it.second.first >= 0 && it.second.third < 100 }
                .sortedWith(compareBy({ it.second.third }, { it.second.second }, { it.second.first }))
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                app.store.updateExtra { l ->
                    l.map { cur ->
                        results.firstOrNull { it.first.id == cur.id }?.let {
                            cur.copy(
                                lat = it.second.first, jitter = it.second.second,
                                loss = it.second.third, testedAt = System.currentTimeMillis()
                            )
                        } ?: cur
                    }
                }
                busy = ""
                val best = alive.firstOrNull()
                if (best == null) {
                    toast("كل السيرفرات غير متاحة الآن — حدّث الاشتراك أو أضف غيره")
                } else {
                    toast("الأفضل: ${best.first.title} (${best.second.first}ms • فقد ${best.second.third}%) — جارٍ الاتصال")
                    connect(best.first)
                }
            }
        }
    }

    /** بعد الفحص: اتصال تلقائي بأفضل سيرفر عامل إن لم يكن هناك نفق قائم. */
    fun autoPick() {
        if (!auto) return
        if (app.store.prefGet("extraAutoConnect") == "0") return
        if (app.engine.status.value.running || app.ovpn.isUp() || XrayEngine.isUp()) return
        val last = app.store.prefGet("extraAutoPickAt")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 10L * 60 * 1000) return
        val best = app.store.extra.value
            .filter { it.protocol in setOf("vmess", "vless", "trojan", "ss") && it.lat >= 0 }
            .sortedWith(rank).firstOrNull() ?: return
        app.store.prefPut("extraAutoPickAt", System.currentTimeMillis().toString())
        toast("اتصال تلقائي بأفضل سيرفر: ${best.title} (${best.lat}ms)")
        connect(best)
    }

    /** فحص جماعي سريع: يبقي المتاح فقط في الأعلى ويعرف المستخدم ما يعمل فعلاً. */
    fun probeAll() {
        if (probing || busy.isNotEmpty()) return
        val targets = app.store.extra.value.filter { it.connectable }.take(120)
        if (targets.isEmpty()) return
        probing = true
        busy = "فحص ${targets.size} سيرفر…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val res = targets.map { s -> async { s.id to ExtraServers.tcpSamples(s.host, s.port) } }
                .map { it.await() }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val now = System.currentTimeMillis()
                app.store.updateExtra { l ->
                    l.map { cur ->
                        res.firstOrNull { it.first == cur.id }?.let { hit ->
                            val t = hit.second
                            cur.copy(lat = t.first, jitter = t.second, loss = t.third, testedAt = now)
                        } ?: cur
                    }
                }
                probing = false
                busy = ""
                toast("فحص ${res.size} سيرفر — ${res.count { it.second.third < 100 }} يعمل")
                autoPick()
            }
        }
    }

    /** طلب HTTPS حقيقي عبر النفق للتأكد من عمل السيرفر فعلًا (لا مجرد TCP). */
    fun tunnelProbe(): Int = try {
        val t0 = System.currentTimeMillis()
        val c = (java.net.URL("https://1.1.1.1/cdn-cgi/trace").openConnection()
                as javax.net.ssl.HttpsURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            instanceFollowRedirects = true
        }
        val code = c.responseCode
        c.inputStream.use { it.readBytes() }
        c.disconnect()
        if (code in 200..399) (System.currentTimeMillis() - t0).toInt() else -1
    } catch (e: Exception) {
        -1
    }

    /** فحص عميق: يفتح نفقًا فعليًا لأفضل 5 مرشحين ويرسل طلباً حقيقياً عبره. */
    fun deepCheck() {
        if (probing || busy.isNotEmpty()) return
        if (app.engine.status.value.running || app.ovpn.isUp() || XrayEngine.isUp()) {
            toast("افصل النفق الحالي أولاً"); return
        }
        if (android.net.VpnService.prepare(ctx) != null) {
            toast("امنح إذن VPN أولاً"); return
        }
        val cands = app.store.extra.value
            .filter { it.protocol in setOf("vmess", "vless", "trojan", "ss") && it.lat >= 0 }
            .sortedWith(rank).take(5)
        if (cands.isEmpty()) {
            toast("اضغط «فحص الكل» أولاً"); return
        }
        probing = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var best: ExtraServer? = null
            var bestMs = Int.MAX_VALUE
            var tried = 0
            for (s in cands) {
                tried++
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    busy = "فحص نفق $tried/${cands.size}…"
                }
                XrayEngine.start(ctx, s, test = true)
                var up = false
                var i = 0
                while (i < 40) {
                    if (XrayEngine.state.value.running) { up = true; break }
                    if (XrayEngine.state.value.error.isNotBlank()) break
                    kotlinx.coroutines.delay(150)
                    i++
                }
                if (up) {
                    val ms = tunnelProbe()
                    if (ms >= 0) {
                        app.store.updateExtra { l ->
                            l.map { if (it.id == s.id) it.copy(note = "✓ نفق") else it }
                        }
                        if (ms < bestMs) { bestMs = ms; best = s }
                    }
                }
                XrayEngine.stop(ctx)
                if (bestMs <= 500) break
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                probing = false
                busy = ""
                val b = best
                if (b == null) {
                    toast("لا سيرفر نجح في فحص النفق — جرّب تحديث الاشتراكات")
                } else {
                    toast("أفضل نفق: ${b.title} • ${bestMs}ms — جارٍ الاتصال")
                    if (autoPickOn) connect(b)
                }
            }
        }
    }

    fun refreshAll(urls: List<String>? = null) {
        val sources0 = urls ?: sources
        if (sources0.isEmpty()) {
            toast("أضف رابط اشتراك أولاً")
            return
        }
        if (busy.isNotEmpty()) return
        busy = "جارٍ جلب ${sources0.size} مصادر…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var got = 0
            var failed = 0
            var firstErr = ""
            val found = ArrayList<ExtraServer>()
            for (u in sources0) {
                try {
                    val text = ExtraServers.fetch(u)
                    val parsed = ExtraServers.parseSubscription(text, u)
                    if (parsed.isEmpty()) {
                        failed++
                        if (firstErr.isEmpty()) firstErr = "لا سيرفرات مفهومة في الرابط"
                    } else {
                        found.addAll(parsed)
                        got += parsed.size
                    }
                } catch (e: Exception) {
                    failed++
                    if (firstErr.isEmpty()) firstErr = (e.message ?: "خطأ شبكة").take(70)
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                addAll(found)
                busy = ""
                toast(
                    if (got == 0) "تعذّر الجلب — $firstErr"
                    else "تم جلب $got سيرفر" + (if (failed > 0) " • تعذّر $failed رابط" else "")
                )
                if (got > 0) probeAll()
            }
        }
    }

    LaunchedEffect(list.count { it.country.isBlank() }) { resolveMissingCountries() }

    // جلب تلقائي عند أول فتح (أو إذا مرّت 6 ساعات) بدل تبويب فارغ
    LaunchedEffect(Unit) {
        val stale = updatedAt == 0L || System.currentTimeMillis() - updatedAt > 6L * 3600 * 1000
        val lastTry = app.store.prefGet("extraAutoAt")?.toLongOrNull() ?: 0L
        if (stale && busy.isEmpty() && sources.isNotEmpty() &&
            System.currentTimeMillis() - lastTry > 5L * 60 * 1000
        ) {
            app.store.prefPut("extraAutoAt", System.currentTimeMillis().toString())
            refreshAll()
        } else if (list.isNotEmpty() && list.none { it.testedAt > 0 } && !probing) {
            probeAll()
        }
    }

    if (showAdd) {
        AddExtraDialog(
            onClose = { showAdd = false },
            onSubmit = { text ->
                val t = text.trim()
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    app.store.setExtraSources(sources + t)
                    showAdd = false
                    toast("أُضيف الرابط — سأجلبه الآن")
                    busy = ""
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val body = ExtraServers.fetch(t)
                            val parsed = ExtraServers.parseSubscription(body, t)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                addAll(parsed)
                                toast(if (parsed.isEmpty()) "الرابط أُضيف لكن لا سيرفرات مفهومة فيه" else "أُضيف ${parsed.size} سيرفر من الرابط")
                            }
                        } catch (e: Exception) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                toast("الرابط أُضيف — تعذّر الجلب الآن: ${(e.message ?: "خطأ شبكة").take(60)}")
                            }
                        }
                    }
                } else {
                    importText(t, "لصق يدوي")
                    showAdd = false
                }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        // شريط Xray رفيع — لا يأكل مساحة القائمة
        if (xr.running || xr.connecting || xr.error.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        xr.running -> "🟢 Xray • " + xr.title
                        xr.connecting -> "🟡 جارٍ الاتصال عبر Xray…"
                        else -> "🔴 Xray: " + xr.error
                    },
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (xr.running || xr.connecting) {
                    TextButton(onClick = { XrayEngine.stop(ctx) }) { Text("فصل") }
                }
            }
        }

        // الرأس داخل القائمة نفسها: يتحرك ويختفي مع السيرفرات (ديناميكي)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, bottom = 12.dp
            )
        ) {
            item(key = "header") {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("سيرفرات مجانية", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                                Text(
                                    (if (updatedAt > 0L) "تحديث " + SimpleDateFormat("HH:mm", Locale.US).format(Date(updatedAt)) else "لم يُحدَّث") +
                                        " • " + list.count { it.lat >= 0 } + " يعمل من " + list.size,
                                    fontSize = 11.sp, maxLines = 1
                                )
                            }
                            if (busy.isNotEmpty()) {
                                Text(busy, fontSize = 11.sp, maxLines = 1)
                            } else {
                                TextButton(onClick = { refreshAll() }) { Text("تحديث") }
                            }
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "إخفاء" else "خيارات")
                            }
                        }
                        if (busy.isNotEmpty()) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (auto) {
                                Button(
                                    onClick = { connectFastest() },
                                    enabled = busy.isEmpty() && list.any { it.connectable },
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) { Text("⚡ اتصال بالأسرع", maxLines = 1) }
                            } else {
                                OutlinedButton(
                                    onClick = { probeAll() },
                                    enabled = busy.isEmpty() && list.any { it.connectable },
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) { Text("فحص الكل", maxLines = 1) }
                            }
                            OutlinedButton(
                                onClick = { showAdd = true },
                                enabled = busy.isEmpty(),
                                modifier = Modifier.height(40.dp)
                            ) { Text("+ اشتراك") }
                        }
                        if (expanded) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = autoPickOn,
                                    onCheckedChange = {
                                        autoPickOn = it
                                        app.store.prefPut("extraAutoConnect", if (it) "1" else "0")
                                    }
                                )
                                Text("اتصال تلقائي بأفضل سيرفر", fontSize = 12.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = !auto, onClick = { setAuto(false) },
                                    label = { Text("يدوي", maxLines = 1) }
                                )
                                FilterChip(
                                    selected = auto, onClick = { setAuto(true) },
                                    label = { Text("تلقائي: الأسرع", maxLines = 1) }
                                )
                                OutlinedButton(
                                    onClick = { deepCheck() },
                                    enabled = busy.isEmpty() && list.any { it.lat >= 0 }
                                ) { Text("فحص عميق", maxLines = 1) }
                            }
                            Text(
                                "المصادر تُحدَّث تلقائياً • " + sources.size + " روابط • " +
                                    "يدعم wg/ovpn/vmess/vless/trojan/ss عبر Xray",
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            if (list.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (busy.isNotEmpty()) busy
                            else "لا سيرفرات بعد — يُجلب تلقائياً عند أول فتح مع إنترنت",
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                items(list.sortedWith(rank), key = { it.id }) { s ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    s.protocol.uppercase(Locale.US),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    s.title, fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (s.country.isNotBlank()) Country.label(s.country) else "🏳️",
                                    fontSize = 12.sp, maxLines = 1
                                )
                            }
                            Text(
                                s.host + ":" + s.port + when {
                                    s.lat >= 0 -> " • ${s.lat}ms • ±${s.jitter}ms • فقد ${s.loss}%" +
                                        if (s.note == "✓ نفق") " • ✓" else ""
                                    s.testedAt > 0 -> " • ❌ غير متاح الآن"
                                    else -> " • لم يُفحص"
                                },
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (!s.connectable) {
                                Text("بروتوكول غير مدعوم للتشغيل", fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (s.connectable) {
                                    Button(onClick = { connect(s) }) { Text("اتصال") }
                                }
                                OutlinedButton(onClick = { testOne(s) }) { Text("فحص") }
                                OutlinedButton(onClick = {
                                    app.store.updateExtra { l -> l.filter { it.id != s.id } }
                                }) { Text("حذف") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddExtraDialog(onClose: () -> Unit, onSubmit: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("إضافة اشتراك / كونفيج") },
        text = {
            Column {
                Text("الصق رابط اشتراك (base64) أو رابط كونفيج أو نص كونفيج wg/ovpn مباشرة.", fontSize = 12.sp)
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    minLines = 4, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                TextButton(onClick = {
                    try {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        val clip = cm.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            text = clip.getItemAt(0).text?.toString() ?: ""
                        }
                    } catch (e: Exception) {
                    }
                }) { Text("لصق من الحافظة") }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(text) }) { Text("إضافة") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("إلغاء") }
        }
    )
}

/** ضوء أحمر ناعم نابض يدل على العنصر الذي اختاره التطبيق. */
@Composable
fun GlowDot(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "glow")
    val a by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).background(Color(0xFFE53935).copy(alpha = a * 0.35f), CircleShape))
        Box(Modifier.size(9.dp).background(Color(0xFFE53935).copy(alpha = 0.55f + a * 0.4f), CircleShape))
    }
}

@Composable
fun MarkerSlot(chosen: Boolean) {
    if (chosen) GlowDot() else Spacer(Modifier.width(18.dp))
    Spacer(Modifier.width(8.dp))
}

/** عمر آخر فحص، بلون يدل على مدى حداثته: أخضر حديث، أصفر متوسط، أحمر قديم. */
@Composable
fun ScanAgeLine(lastScanAt: Long) {
    if (lastScanAt <= 0L) {
        Text("آخر فحص: لم يُجرَ بعد", fontSize = 12.sp)
        return
    }
    val ageMs = System.currentTimeMillis() - lastScanAt
    val text = when {
        ageMs < 60_000 -> "آخر فحص: قبل ${(ageMs / 1000).coerceAtLeast(1)} ثانية"
        ageMs < 3_600_000 -> "آخر فحص: قبل ${ageMs / 60_000} دقيقة"
        else -> "آخر فحص: قبل ${ageMs / 3_600_000} ساعة"
    }
    val color = when {
        ageMs < 2 * 60_000 -> Color(0xFF2E7D32)
        ageMs < 20 * 60_000 -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.error
    }
    Text(text, fontSize = 12.sp, color = color)
}

private fun rowLine(r: ConfigRow): String = when {
    r.sameCountry -> "⚠ نفس دولتك ${Country.label(r.exitLoc)} — لا يفيد لتجاوز الحجب، مستبعَد تلقائياً"
    r.state == "pending" -> "بانتظار الفحص"
    r.state == "testing" -> "جارٍ الفحص…"
    r.state == "fail" -> "✗ ${r.note}"
    else -> buildString {
        append("${r.latencyMs} ms")
        if (r.jitter > 0) append(" ±${r.jitter}")
        if (r.score > 0) append(" • فقد ${r.loss}%")
        r.mbps?.let { append(" • %.1f Mbps".format(it)) }
        if (r.mtu > 0) append(" • MTU ${r.mtu}")
        if (r.exitIp.isNotEmpty()) append(" • ${Country.ipLine(r.exitIp, r.exitLoc)}")
    }
}

@Composable
fun ResultsCard(rep: Report, st: Status) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("نتيجة الفحص — امتحان عادل للجميع", fontWeight = FontWeight.Bold)
            Text(Exam.FORMULA, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rep.progress.isNotEmpty()) Text(rep.progress, fontSize = 12.sp)
            val rows = rep.rows.sortedWith(
                compareBy<ConfigRow>(
                    { when (it.state) { "ok" -> 0; "testing" -> 1; "pending" -> 2; else -> 3 } },
                    { -it.score },
                    { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs }
                )
            )
            var rank = 0
            rows.forEach { r ->
                val chosen = st.connected && r.id == rep.chosenConfigId
                val badge = if (r.state == "ok") { rank++; Exam.rankMedal(rank - 1) } else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkerSlot(chosen)
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (badge != null) "$badge • " else "") + r.name,
                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            rowLine(r), fontSize = 12.sp,
                            color = when {
                                r.sameCountry -> Color(0xFFF9A825)
                                r.state == "fail" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
            if (rep.dnsRows.isNotEmpty()) {
                HorizontalDivider()
                Text("DNS عبر النفق (الأفضل جودةً أولاً)", fontWeight = FontWeight.Bold)
                rep.dnsRows.take(6).forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarkerSlot(st.connected && d.id == rep.chosenDnsId)
                        Text(
                            "${d.name}: " + if (d.ms < 0) "لا استجابة (0%)"
                            else "${d.ms} ms ±${d.jitter} • فقد ${d.loss}%",
                            fontSize = 13.sp
                        )
                    }
                }
            }
            if (rep.chosenMtu > 0) {
                HorizontalDivider()
                Text("MTU المختار: ${rep.chosenMtu}", fontWeight = FontWeight.Bold)
                Text(
                    "اختير بالتجربة الفعلية عبر النفق: أعلى MTU يمرّ فيه HTTPS بزمن مقارب للمرجع " +
                        "(لا نعتمد على ICMP لأنه يعطي أرقاماً أقل من الحقيقة في كثير من الشبكات).",
                    fontSize = 12.sp
                )
                if (rep.mtuRows.isNotEmpty()) {
                    Text("نتائج التجربة (سرعة عبر النفق لكل MTU، «فشل» = لا يمر HTTPS):", fontSize = 12.sp)
                    rep.mtuRows.forEach { m ->
                        Text(
                            "  MTU ${m.mtu}: " + (m.mbps?.let { "%.1f Mbps".format(it) } ?: "فشل") +
                                if (m.mtu == rep.chosenMtu) "  ← المختار" else "",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HomeTab(app: WgApp, a: Actions) {
    val st by app.engine.status.collectAsState()
    val logs by app.engine.logs.collectAsState()
    val ni by app.engine.netInfo.collectAsState()
    val rep by app.engine.report.collectAsState()
    val cfgs by app.store.configs.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("WG", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Column {
                Text("WG Auto", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("اختيار تلقائي للكونفيج وDNS وMTU", fontSize = 12.sp)
            }
        }
        Button(
            onClick = a.toggle,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (st.running) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                when {
                    st.running && st.busy -> "جارٍ الفحص… (إيقاف)"
                    st.running -> "إيقاف"
                    else -> "تشغيل"
                },
                fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(st.message.ifEmpty { "متوقف" }, fontWeight = FontWeight.Bold)
                ScanAgeLine(st.lastScanAt)
                if (st.killSwitchOn) {
                    Text("🔒 قاطع الطوارئ مفعّل — لا إنترنت خارج النفق", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                if (st.connected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarkerSlot(true)
                        Text("الكونفيج: ${st.activeConfig}", fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarkerSlot(true)
                        Text("DNS: ${st.activeDns}", fontWeight = FontWeight.Bold)
                    }
                    Text("MTU: ${st.activeMtu}")
                    Text("التأخر عبر النفق: ${st.latencyMs} ms")
                    if (st.mbps != null) Text("السرعة: ${"%.1f Mbps".format(st.mbps)}")
                    HorizontalDivider()
                    Text("دليل أن النفق حقيقي", fontWeight = FontWeight.Bold)
                    if (st.diag.isNotEmpty()) Text(st.diag, fontSize = 13.sp)
                    if (st.vpnIface.isNotEmpty()) Text("واجهة VPN: ${st.vpnIface}", fontSize = 13.sp)
                    if (st.exitIp.isNotEmpty()) Text("IP عبر النفق: ${Country.ipLine(st.exitIp, st.exitLoc)}", fontSize = 13.sp)
                    if (st.directIp.isNotEmpty()) Text("IP المباشر: ${Country.ipLine(st.directIp, st.directLoc)}", fontSize = 13.sp)
                    Text(
                        if (st.verified) "✔ تغيّر الـ IP: الحركة تمر فعلاً عبر النفق"
                        else "⚠ لم يُتحقق من تغيّر الـ IP (تحقق بنفسك من موقع فحص IP)",
                        fontSize = 13.sp,
                        color = if (st.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        if (rep.rows.isNotEmpty() || rep.progress.isNotEmpty()) ResultsCard(rep, st)
        // زر الفحص الشامل دائم الظهور — حتى بدون تشغيل: يفحص الجميع ويعرض العامل فقط
        if (!st.running && !st.busy) {
            val n = cfgs.count { it.enabled }
            OutlinedButton(
                onClick = { a.testAll() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("🔍 فحص شامل للجميع ($n) — بدون اتصال") }
            Text(
                "يفحص كل الكونفيجات المفعّلة واحداً واحداً ويعرض بجانب كل واحد: العلم والدولة وIP والزمن — ثم تختار أو تشغّل.",
                fontSize = 12.sp
            )
        }
        if (st.running && !st.busy) {
            var showFullScanConfirm by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    if (st.connected) app.engine.opportunisticCheck("يدوي") else app.engine.reselect("يدوي")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (st.connected) "بحث عن كونفيج أفضل (بلا قطع الاتصال)" else "فحص وإعادة اختيار الكونفيج الآن") }
            if (st.connected) {
                TextButton(
                    onClick = { showFullScanConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("بحث كامل شامل — قد يقطع الاتصال مؤقتاً") }
            }
            if (showFullScanConfirm) {
                AlertDialog(
                    onDismissRequest = { showFullScanConfirm = false },
                    title = { Text("بحث كامل شامل") },
                    text = { Text("سيُعاد فحص كل الكونفيجات من الصفر، وقد ينقطع الاتصال الحالي مؤقتاً أثناء ذلك (قد يستغرق دقائق). متابعة؟") },
                    confirmButton = {
                        TextButton(onClick = { showFullScanConfirm = false; app.engine.reselect("بحث كامل يدوي") }) {
                            Text("متابعة", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { showFullScanConfirm = false }) { Text("إلغاء") } }
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("فحص الشبكة (مستقل عن الـ VPN)", fontWeight = FontWeight.Bold)
                val d = ni.direct
                if (d == null) {
                    Text("لم يُفحص بعد")
                } else {
                    SideView("الشبكة الأصلية", d, false)
                    ni.vpn?.let { v ->
                        HorizontalDivider()
                        SideView("عبر VPN (${ni.vpnOwner})", v, true)
                    }
                    if (ni.time.isNotEmpty()) Text("آخر فحص: ${ni.time}", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { app.engine.scanNetwork("يدوي") },
                    enabled = !st.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("فحص الشبكة الآن") }
            }
        }
        var showShutdown by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showShutdown = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) { Text("⛔ إغلاق نهائي شامل") }
        if (showShutdown) {
            AlertDialog(
                onDismissRequest = { showShutdown = false },
                title = { Text("إغلاق نهائي شامل") },
                text = {
                    Text(
                        "سيوقف النفق وقاطع الطوارئ والفحص التلقائي في الخلفية، ويستعيد إعداد DNS الأصلي للنظام. " +
                            "لن يعمل شيء بعدها حتى تفتح التطبيق وتضغط تشغيل يدوياً."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showShutdown = false; a.shutdownAll() }) {
                        Text("إغلاق", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showShutdown = false }) { Text("إلغاء") } }
            )
        }
        var showLog by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showLog = !showLog },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (showLog) "▲ إخفاء سجل الفحص" else "▼ سجل الفحص التفصيلي (يُحذف تلقائياً بعد يوم)") }
        if (showLog) {
            Text("الأحدث أولاً — للمراجعة عند الحاجة فقط", fontSize = 11.sp)
            logs.reversed().take(100).forEach { Text(it, fontSize = 12.sp) }
        }
    }
}

@Composable
fun SideView(title: String, sd: SideInfo, vpnSide: Boolean) {
    Text(title, fontWeight = FontWeight.Bold)
    Text(sd.label + if (sd.iface.isNotEmpty()) " (${sd.iface})" else "")
    if (sd.dnsMs >= 0) {
        Text("أسرع DNS: ${sd.bestDns} — ${sd.dnsMs} ms (${sd.dnsProto}، فقد ${sd.dnsLoss}%)")
    } else {
        Text("DNS: لا استجابة من أي خادم (المنفذ 53 قد يكون محجوباً من مزودك)")
    }
    if (sd.sysDnsMs >= 0) Text("DNS الحالي للنظام: ${sd.sysDnsMs} ms", fontSize = 13.sp)
    if (sd.latencyMs >= 0) Text("التأخر: ${sd.latencyMs} ms")
    if (sd.pathMtu > 0) {
        if (vpnSide) {
            Text("أقصى حزمة تمر: ${sd.pathMtu}" + if (sd.ifaceMtu > 0) " • MTU الواجهة: ${sd.ifaceMtu}" else "")
        } else {
            Text("أقصى حزمة للمسار (ICMP): ${sd.pathMtu} • مقترح مبدئي: ${sd.suggestedMtu}")
        }
    } else {
        Text(if (vpnSide) "MTU: تعذّر الفحص" else "MTU: يحتاج ping أو روت أثناء وجود VPN")
    }
}

@Composable
fun ConfigsTab(app: WgApp, a: Actions) {
    val list by app.store.configs.collectAsState()
    val s by app.store.settings.collectAsState()
    val results by app.engine.configResults.collectAsState()
    val st by app.engine.status.collectAsState()
    var showPaste by remember { mutableStateOf(false) }
    var webServer by remember { mutableStateOf<VpnBook.Server?>(null) }

    // شاشة المولد الداخلي تحتل التبويب كاملاً (إنجاز: توليد وتنزيل دون مغادرة التطبيق)
    if (webServer != null) {
        val ws = webServer!!
        VpnBookWebView(
            app = app,
            hint = "اختر في الصفحة: ${Country.label(ws.country)} ${ws.label} — المنفذ 443",
            onClose = { webServer = null }
        )
        return
    }

    // قائمة ديناميكية واحدة مثل تبويب VPN: الأدوات تُمرَّر والكونفيجات ظاهرة دائماً
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = a.importFiles, modifier = Modifier.weight(1f)) { Text("استيراد", maxLines = 1) }
            OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.weight(1f)) { Text("لصق", maxLines = 1) }
            OutlinedButton(onClick = a.testAll, modifier = Modifier.weight(1f)) { Text("فحص الكل", maxLines = 1) }
        }
        val warpStatus by app.engine.warpStatus.collectAsState()
        val warpPin by app.store.settings.collectAsState()
        OutlinedButton(
            onClick = { app.engine.generateWarp() },
            enabled = warpStatus != "جارٍ التسجيل في WARP…",
            modifier = Modifier.fillMaxWidth()
        ) { Text("توليد كونفيج Cloudflare WARP مجاني") }
        Picker(
            "خروج WARP المطلوب",
            when (warpPin.warpCountry) {
                "DE" -> "ألمانيا"
                "FR" -> "فرنسا"
                "NL" -> "هولندا"
                else -> "أي أجنبي (غير سوريا)"
            },
            listOf("" to "أي أجنبي (غير سوريا)", "DE" to "ألمانيا", "FR" to "فرنسا", "NL" to "هولندا")
        ) { v -> app.store.updateSettings { it.copy(warpCountry = v) } }
        if (warpStatus.isNotEmpty()) {
            Text(
                warpStatus, fontSize = 12.sp,
                color = if (warpStatus.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        var showProton by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showProton = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🇨🇭 خوادم Proton المجانية (خروج أجنبي)") }
        if (showProton) ProtonDialog(
            app,
            onClose = { showProton = false }
        )
        var showBook by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showBook = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("📖 VPNBook مجاني — أمريكا/ألمانيا/فرنسا (7 أيام)") }
        if (showBook) VpnBookDialog(
            onClose = { showBook = false },
            onImport = { a.importFiles() },
            onOpenInApp = { srv -> showBook = false; webServer = srv }
        )
        var showAI by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showAI = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🧠 مستشار الذكاء: اختر لي أفضل كونفيج وDNS وMTU وسيرفر") }
        if (showAI) AIAdvisorDialog(app, onClose = { showAI = false })
        var repairMsg by remember { mutableStateOf("") }
        OutlinedButton(
            onClick = {
                val (ok, fail) = app.engine.repairAllConfigs()
                repairMsg = "عُولج $ok كونفيج • فشل $fail — لا حاجة لحذف DNS يدوياً بعد الآن"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🔧 إصلاح الكونفيجات المستوردة تلقائياً") }
        if (repairMsg.isNotEmpty()) Text(repairMsg, fontSize = 12.sp)
        Text(
            "يدعم ملفات .conf وملف .zip يحوي عدة كونفيجات. تُفحص الكونفيجات تلقائياً عند تشغيل الـ VPN فقط.",
            fontSize = 12.sp
        )
        if (list.isEmpty()) Text("لا توجد كونفيجات بعد.")
        }
        items(list, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // السطر 1: الاسم + العلم (جدول منظم)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!s.autoConfig) {
                                RadioButton(
                                    selected = s.selConfigId == c.id,
                                    onClick = { app.store.updateSettings { it.copy(selConfigId = c.id) } }
                                )
                            }
                            MarkerSlot(st.connected && st.activeConfigId == c.id)
                            Text(
                                c.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            if (c.lastCountry.isNotEmpty()) {
                                Text(Country.label(c.lastCountry), fontSize = 13.sp, maxLines = 1)
                            }
                            Switch(
                                checked = c.enabled,
                                onCheckedChange = { v ->
                                    app.store.updateConfigs { l -> l.map { if (it.id == c.id) it.copy(enabled = v) else it } }
                                }
                            )
                        }
                        // السطر 2: الخادم + IP الخروج (إن عُرف)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val ep = Probes.parseEndpoint(c.text)
                            Text(
                                ep?.let { "🖥 ${it.first}:${it.second}" } ?: "—",
                                fontSize = 12.sp, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // سطر الانتهاء (VPNBook): تحذير أحمر عند القرب
                        VpnBook.expiryText(c.expiresAt)?.let { ex ->
                            val urgent = VpnBook.expiringSoon(c.expiresAt) || VpnBook.expired(c.expiresAt)
                            Text(
                                ex, fontSize = 12.sp,
                                color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // السطر 3: نتيجة الفحص فقط (بلا سلسلة كتابات)
                        results[c.id]?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isTargeted = !s.autoConfig && s.selConfigId == c.id
                            val isLiveConnected = st.connected && st.activeConfigId == c.id
                            TextButton(onClick = {
                                if (isTargeted) {
                                    // إيقاف هذا الكونفيج فقط — لا يُغيَّر إعداد الاختيار التلقائي
                                    // ولا يبدأ أي فحص شامل؛ العودة للتلقائي فعل منفصل ومقصود.
                                    if (st.running) app.engine.stop()
                                } else {
                                    a.activateConfig(c.id)
                                }
                            }) {
                                Text(
                                    when {
                                        isLiveConnected -> "✔ متصل فعلياً"
                                        isTargeted && st.busy -> "⏳ جارٍ الاتصال…"
                                        isTargeted -> "⏸ إيقاف (لم يتصل بعد)"
                                        else -> "▶ تفعيل"
                                    },
                                    color = when {
                                        isLiveConnected -> Color(0xFF2E7D32)
                                        isTargeted && st.busy -> Color(0xFFF9A825)
                                        isTargeted -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            TextButton(onClick = { a.testOne(c.id) }) { Text("فحص") }
                            TextButton(onClick = { app.store.updateConfigs { l -> l.filter { it.id != c.id } } }) {
                                Text("حذف", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        item {
        if (!s.autoConfig) {
            OutlinedButton(
                onClick = {
                    app.store.updateSettings { it.copy(autoConfig = true) }
                    if (st.running) app.engine.reselect("العودة للاختيار التلقائي")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("العودة للاختيار التلقائي") }
        }
        }
    }

    if (showPaste) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("إضافة كونفيج") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(text, { text = it }, label = { Text("نص الكونفيج") }, minLines = 6, maxLines = 10)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = app.engine.addConfig(name, text)
                    if (e == null) {
                        showPaste = false
                    } else {
                        err = e
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun ProtonDialog(app: WgApp, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(ProtonServers.cache) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    // جلب أول مرة تلقائياً
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (list.isEmpty()) {
            loading = true
            list = ProtonServers.refresh()
            err = ProtonServers.lastError
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Proton Free — خروج أجنبي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "حساب Proton المجاني يعطي خروجاً أجنبياً حقيقياً (هولندا/أمريكا/اليابان) بلا دفع. التوليد التلقائي الكامل يحتاج تسجيل دخول، لذلك نعرض أخف الخوادم المجانية لتختارها في موقع Proton ثم تستورد ملف .conf هنا.",
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            loading = true
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                list = ProtonServers.refresh()
                                err = ProtonServers.lastError
                                loading = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (loading) "جارٍ التحديث…" else "تحديث القائمة") }
                    OutlinedButton(
                        onClick = {
                            try {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads"))
                                )
                            } catch (e: Exception) { }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("فتح صفحة التحميل (متصفح خارجي)", maxLines = 1) }
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (list.isEmpty() && !loading) Text("لا توجد بيانات بعد — اضغط تحديث القائمة", fontSize = 12.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list.take(30), key = { it.domain + it.entryIp }) { s ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${Country.label(s.country)} • ${s.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${s.entryIp} • حمل ${s.load}%", fontSize = 12.sp)
                                Text(s.domain, fontSize = 11.sp)
                            }
                        }
                    }
                }
                Text("بعد التحميل من الموقع: عُد هنا واضغط استيراد واختر ملف .conf — سيظهر العلم تلقائياً.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
        dismissButton = null
    )
}

@Composable
fun VpnBookDialog(onClose: () -> Unit, onImport: () -> Unit, onOpenInApp: (VpnBook.Server) -> Unit) {
    val ctx = LocalContext.current
    var pings by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var pinging by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VpnBook.Server?>(null) }

    fun refresh() {
        if (pinging) return
        pinging = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val res = VpnBook.servers.map { s ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    s.host to Probes.pingOnce(s.host)
                }
            }.map { it.await() }.toMap()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                pings = res
                pinging = false
                // اختيار تلقائي لأسرع مستجيب عند أول قياس
                if (selected == null) {
                    res.entries.filter { it.value != null }.minByOrNull { it.value!! }?.let { best ->
                        selected = VpnBook.servers.firstOrNull { it.host == best.key }
                    }
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    val sorted = VpnBook.servers.sortedWith(
        compareBy<VpnBook.Server> { pings[it.host] ?: Int.MAX_VALUE }.thenBy { it.country }
    )
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("VPNBook مجاني — خروج أجنبي حقيقي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "اختر سيرفراً (يُقاس تلقائياً ويُختار الأسرع)، ثم افتح المولد داخل التطبيق " +
                        "واختر نفس السيرفر والمنفذ 443 — الملف المولّد يُستورد مباشرة لقائمتك.",
                    fontSize = 12.sp
                )
                if (selected != null) {
                    Text(
                        "المختار: ${Country.label(selected!!.country)} ${selected!!.label} — افتح المولد واختره هناك",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onOpenInApp(selected ?: sorted.firstOrNull() ?: VpnBook.servers.first()) },
                        modifier = Modifier.weight(1f)
                    ) { Text("فتح المولد داخل التطبيق", maxLines = 1) }
                    OutlinedButton(onClick = { onImport() }, modifier = Modifier.weight(1f)) { Text("استيراد .conf", maxLines = 1) }
                }
                TextButton(
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VpnBook.GEN_URL)))
                        } catch (e: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("أو فتح في المتصفح الخارجي", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { refresh() },
                    enabled = !pinging,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (pinging) "جارٍ قياس السرعة…" else "إعادة قياس سرعة السيرفرات") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sorted, key = { it.host }) { s ->
                        val rtt = pings[s.host]
                        val isSel = selected?.host == s.host
                        Card(
                            Modifier.fillMaxWidth().clickable { selected = s },
                            colors = if (isSel) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else CardDefaults.cardColors()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    (if (isSel) "✔ " else "") + "${Country.label(s.country)} • ${s.label}",
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                                )
                                Text(
                                    s.host + " • " + if (pings.isEmpty() || (pinging && rtt == null)) "…"
                                    else rtt?.let { "$it ms" } ?: "لا يرد ping",
                                    fontSize = 12.sp
                                )
                                Text("المنفذ المقترح: 443 (ثم 123 ثم 80)", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
        dismissButton = null
    )
}

@Composable
fun VpnBookWebView(app: WgApp, hint: String, onClose: () -> Unit) {
    VpnBookWebView(
        app = app, title = "مولد VPNBook داخل التطبيق", url = VpnBook.GEN_URL,
        namePrefix = "vpnbook", hint = hint, onClose = onClose
    )
}

@Composable
fun VpnBookWebView(
    app: WgApp, title: String, url: String, namePrefix: String, hint: String, onClose: () -> Unit,
    expectOvpn: Boolean = false, onOvpn: ((String, String) -> Unit)? = null
) {
    fun isValidConfig(text: String): Boolean = if (expectOvpn)
        text.contains("remote ") && (text.contains("client", true) || text.contains("dev tun", true))
    else text.contains("[Interface]") && text.contains("[Peer]")
    var statusMsg by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember { mutableStateOf(url) }
    var pageState by remember { mutableStateOf("يفتح…") }
    var progress by remember { mutableIntStateOf(0) }
    var showWvUpdate by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // يستقبل نص الكونفيج من JS (blob أو التقاط ظاهر) ويدخله للقائمة — يعمل على أي خيط
    fun importFromJs(raw: String, fnameHint: String) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var msg: String
            try {
                // نص evaluateJavascript قد يصل JSON-mquoted — فكّه إن لزم
                var text = raw
                if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
                    text = text.substring(1, text.length - 1)
                        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                        .replace("\\\"", "\"").replace("\\\\", "\\")
                }
                // فك ترميز \uXXXX إن وجد (مفاتيح WireGuard عادة ASCII لكن احتياط)
                if (text.contains("\\u")) {
                    try {
                        val sb = StringBuilder()
                        var i = 0
                        while (i < text.length) {
                            if (text[i] == '\\' && i + 5 < text.length && text[i + 1] == 'u') {
                                val hex = text.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    i += 6
                                    continue
                                }
                            }
                            sb.append(text[i])
                            i++
                        }
                        text = sb.toString()
                    } catch (_e: Exception) { }
                }
                if (!isValidConfig(text)) {
                    msg = "لم أجد كونفيج في الصفحة بعد — انتظر ظهوره ثم اضغط التقاط مجدداً"
                } else if (expectOvpn && onOvpn != null) {
                    val cleanName = fnameHint.substringAfterLast('/').substringAfterLast('\\')
                        .substringBeforeLast('.').ifBlank { "$namePrefix-${System.currentTimeMillis()}" }
                    onOvpn(text, cleanName)
                    msg = "✔ استُلم كونفيج OpenVPN — جارٍ تجهيزه كسيرفر…"
                } else {
                    val cleanName = fnameHint.substringAfterLast('/').substringAfterLast('\\')
                        .substringBeforeLast('.').ifBlank { "$namePrefix-${System.currentTimeMillis()}" }
                    val err = app.engine.addConfig(cleanName, text)
                    msg = if (err == null) "✔ تم إدخال $cleanName في قائمة الكونفيجات (ارجع وافحصه)"
                    else "✗ $err"
                }
            } catch (e: Exception) {
                msg = "فشل الاستيراد: ${e.message?.take(100)}"
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                downloading = false
                statusMsg = msg
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onClose) { Text("رجوع ✕") }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(hint, fontSize = 12.sp, maxLines = 2)
                Text("الحالة: $pageState • ${pageUrl.take(70)}", fontSize = 10.sp, maxLines = 2)
            }
        }
        // زر التقاط احتياطي — لا يعطّل التنزيل التلقائي، فقط بديل عند فشل blob
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val wv = webRef
                    if (wv == null) {
                        statusMsg = "المتصفح لم يجهز بعد"
                        return@OutlinedButton
                    }
                    downloading = true
                    statusMsg = ""
                    wv.evaluateJavascript(
                        "(function(){try{" +
                            "var t=(document.body?document.body.innerText:'');" +
                            "var i=t.indexOf('" + (if (expectOvpn) "remote " else "[Interface]") + "');" +
                            "if(i<0){AndroidBlob.onError('NO_CONFIG');return;}" +
                            "AndroidBlob.onBlob(t.substring(i,i+9000));" +
                            "}catch(e){AndroidBlob.onError(''+e)}})();",
                        null
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("📋 التقاط الكونفيج الظاهر", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) { }
                },
                modifier = Modifier.weight(1f)
            ) { Text("فتح خارجي", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    val wv = webRef
                    if (wv == null) statusMsg = "المتصفح لم يجهز بعد"
                    else {
                        downloading = true
                        statusMsg = ""
                        wv.reload()
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("↻ تحميل", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    val wv = webRef
                    if (wv == null) statusMsg = "المتصفح لم يجهز بعد"
                    else {
                        try {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            wv.clearCache(true)
                        } catch (_: Exception) { }
                        statusMsg = "مُسحت بيانات المتصفح — أعد التحميل وسجّل من جديد"
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("مسح البيانات", maxLines = 1) }
        }
        if (statusMsg.isNotEmpty() || downloading) {
            Text(
                if (downloading) "⏳ جارٍ تنزيل الملف وإدخاله للقائمة…" else statusMsg,
                fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (showWvUpdate) {
            OutlinedButton(
                onClick = {
                    try {
                        ctx.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")
                            )
                        )
                    } catch (_: Exception) { }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) { Text("تحديث Android System WebView من المتجر") }
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // صفحات Proton (https) تحمّل موارد مختلطة — الوضع الافتراضي
                    // يمنعها فينقطع التحميل وتبقى الصفحة بيضاء.
                    if (Build.VERSION.SDK_INT >= 21) {
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // بعض المواقع (ومنها بروتون وCloudflare) تعطي صفحة بيضاء عند كشف WebView
                    // عبر علامة "; wv" أو وكيل قديم — وكيل كروم حديث كامل يمنع الكشف.
                    try {
                        var ua = settings.userAgentString ?: ""
                        ua = ua.replace("; wv", "")
                        if (!Regex("Chrome/1[2-9]\\d").containsMatchIn(ua)) {
                            ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                        }
                        settings.userAgentString = ua
                    } catch (_: Exception) { }
                    // صفحات الدخول تفتح نوافذ منبثقة — بدون هذا تبقى الشاشة بيضاء.
                    // نحمّلها في نفس المتصفح ونعرض أخطاء وحدة التحكم للتشخيص.
                    try {
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                    } catch (_: Exception) { }
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    val wv = this
                    // Google/Apple ترفض تسجيل الدخول داخل WebView (سياسة ثابتة) —
                    // أي رابط دخول خارجي يُفتح في المتصفح تلقائياً بدل البياض.
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: ""
                            val host = (request?.url?.host ?: "").lowercase()
                            val externalAuth = host.contains("accounts.google.com") ||
                                host.contains("appleid.apple.com") ||
                                host.contains("facebook.com") ||
                                host.contains("oauth") ||
                                u.contains("sso")
                            if (externalAuth) {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, request!!.url))
                                    wv.post {
                                        statusMsg = "تسجيل الدخول عبر Google يعمل في المتصفح فقط (قرار Google) — " +
                                            "سجّل هناك، ثم ادخل هنا بالبريد وكلمة المرور، أو حمّل الملف خارجياً واستورده بزر استيراد"
                                    }
                                } catch (_: Exception) { }
                                return true
                            }
                            // روابط التطبيقات (intent:// وغير http) — خارج التطبيق بدل التعليق
                            if (!u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("about:")) {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, request!!.url))
                                } catch (_: Exception) { }
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            wv.post {
                                if (!url.isNullOrEmpty()) pageUrl = url
                                pageState = "يحمّل…"
                                progress = 0
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            wv.post {
                                if (downloading) downloading = false
                                if (!url.isNullOrEmpty()) pageUrl = url
                                pageState = "اكتمل التحميل"
                            }
                            // تشخيص الفراغ: بعد 8 ثوانٍ إن بقي body فارغاً فالصفحة محجوبة
                            // (Cloudflare ضد المتصفح الداخلي) أو WebView النظام قديم — نرشد بدل البياض الصامت.
                            wv.postDelayed({
                                try {
                                    wv.evaluateJavascript(
                                        "(function(){return (document.body?document.body.innerText:'').length;})();"
                                    ) { res ->
                                        val len = res?.trim()?.trim('"')?.toIntOrNull() ?: -1
                                        if (len == 0 && !pageState.startsWith("خطأ")) {
                                            statusMsg = "الصفحة فارغة بعد التحميل — حجب Cloudflare للمتصفح الداخلي أو WebView قديم: " +
                                                "حدّث Android System WebView من المتجر (زر بالأسفل)، أو استخدم «فتح خارجي» ثم استيراد الملف"
                                            showWvUpdate = true
                                        }
                                    }
                                } catch (_: Exception) { }
                            }, 8000)
                        }

                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                wv.post {
                                    downloading = false
                                    pageState = "خطأ: ${error?.description ?: "شبكة"}"
                                    statusMsg = "تعذّر تحميل الصفحة (${error?.description ?: "خطأ شبكة"}) — تحقق من الإنترنت ثم ↻ تحميل، أو افتح خارجياً"
                                }
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            // النوافذ المبرمجة (window.open في صفحات الدخول) بلا hitTestResult —
                            // النقل الرسمي عبر WebViewTransport يمنع موت النافذة والبياض.
                            try {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport
                                if (transport != null) {
                                    val popup = WebView(c)
                                    popup.settings.javaScriptEnabled = true
                                    popup.settings.domStorageEnabled = true
                                    transport.webView = popup
                                    resultMsg.sendToTarget()
                                    // اعرض النتيجة في نفس المتصفح بدل نافذة منفصلة
                                    popup.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            v: WebView?, req: WebResourceRequest?
                                        ): Boolean {
                                            val u = req?.url?.toString() ?: return true
                                            if (u.startsWith("http://") || u.startsWith("https://")) {
                                                wv.post { wv.loadUrl(u) }
                                                popup.destroy()
                                                return true
                                            }
                                            return true
                                        }
                                    }
                                    return true
                                }
                            } catch (_: Exception) { }
                            val href = view?.hitTestResult?.extra
                            if (!href.isNullOrBlank()) {
                                wv.post { wv.loadUrl(href) }
                                return true
                            }
                            return false
                        }

                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            if (msg?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                val line = (msg.message() ?: "").take(140)
                                if (line.isNotBlank()) {
                                    wv.post {
                                        statusMsg = "خطأ الصفحة: $line"
                                    }
                                }
                            }
                            return super.onConsoleMessage(msg)
                        }
                    }

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onBlob(text: String) {
                            importFromJs(text, "$namePrefix-${System.currentTimeMillis()}.conf")
                        }
                        @JavascriptInterface
                        fun onError(err: String) {
                            wv.post {
                                downloading = false
                                statusMsg = if (err == "NO_CONFIG")
                                    "لا كونفيج ظاهر بعد — اضغط Generate في الصفحة أولاً ثم التقاط"
                                else "تعذّر قراءة الملف من الصفحة ($err) — جرّب التقاط مجدداً"
                            }
                        }
                    }, "AndroidBlob")
                    webRef = this
                    setDownloadListener { url, userAgent, contentDisposition, _, _ ->
                        downloading = true
                        statusMsg = ""
                        if (url.startsWith("blob:", true)) {
                            // ملفات VPNBook تُولَّد كـ blob داخل الصفحة — HttpURLConnection لا يفهمها،
                            // فنقرأها عبر fetch داخل الـ WebView نفسه ثم نستوردها.
                            val safe = url.replace("'", "")
                            wv.evaluateJavascript(
                                "(function(){fetch('$safe').then(function(r){return r.text();})" +
                                    ".then(function(t){AndroidBlob.onBlob(t);})" +
                                    ".catch(function(e){AndroidBlob.onError(''+e);});})();",
                                null
                            )
                            return@setDownloadListener
                        }
                        if (url.startsWith("data:", true)) {
                            // data:text/plain;base64,... — فك مباشر بلا شبكة
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                var msg: String
                                try {
                                    val comma = url.indexOf(',')
                                    val meta = url.substring(5, if (comma >= 0) comma else url.length)
                                    var data = if (comma >= 0) url.substring(comma + 1) else ""
                                    val text = if (meta.contains(";base64", true)) {
                                        String(
                                            android.util.Base64.decode(data, android.util.Base64.DEFAULT),
                                            Charsets.UTF_8
                                        )
                                    } else {
                                        java.net.URLDecoder.decode(data, "UTF-8")
                                    }
                                    if (isValidConfig(text)) {
                                        if (expectOvpn && onOvpn != null) {
                                            onOvpn(text, "$namePrefix-${System.currentTimeMillis()}")
                                            msg = "✔ استُلم كونفيج OpenVPN — جارٍ تجهيزه كسيرفر…"
                                        } else {
                                            val err = app.engine.addConfig(
                                                "$namePrefix-${System.currentTimeMillis()}", text
                                            )
                                            msg = if (err == null) "✔ تم إدخال الكونفيج في القائمة (ارجع وافحصه)"
                                            else "✗ $err"
                                        }
                                    } else {
                                        msg = if (expectOvpn) "الملف المحمّل ليس كونفيج OpenVPN صالحاً"
                                        else "الملف المحمّل ليس كونفيج WireGuard صالحاً"
                                    }
                                } catch (e: Exception) {
                                    msg = "فشل التنزيل: ${e.message?.take(100)}"
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    downloading = false
                                    statusMsg = msg
                                }
                            }
                            return@setDownloadListener
                        }
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            var msg: String
                            try {
                                val cookie = CookieManager.getInstance().getCookie(url)
                                val conn = URL(url).openConnection() as HttpURLConnection
                                if (cookie != null) conn.setRequestProperty("Cookie", cookie)
                                conn.setRequestProperty("User-Agent", userAgent)
                                conn.connectTimeout = 15000
                                conn.readTimeout = 15000
                                val code = conn.responseCode
                                val text = conn.inputStream.bufferedReader().use { it.readText() }
                                conn.disconnect()
                                if (code in 200..299 && isValidConfig(text)) {
                                    var fname = Regex("filename=\"?([^\";]+)\"?").find(contentDisposition ?: "")
                                        ?.groupValues?.getOrNull(1) ?: "$namePrefix-${System.currentTimeMillis()}.conf"
                                    fname = fname.substringAfterLast('/').substringAfterLast('\\')
                                    if (expectOvpn && onOvpn != null) {
                                        onOvpn(text, fname.substringBeforeLast('.'))
                                        msg = "✔ استُلم $fname — جارٍ تجهيزه كسيرفر OpenVPN…"
                                    } else {
                                        val err = app.engine.addConfig(fname.substringBeforeLast('.'), text)
                                        msg = if (err == null) "✔ تم إدخال $fname في قائمة الكونفيجات (ارجع وافحصه)"
                                        else "✗ $err"
                                    }
                                } else {
                                    msg = if (expectOvpn) "الملف المحمّل ليس كونفيج OpenVPN صالحاً (HTTP $code)"
                                    else "الملف المحمّل ليس كونفيج WireGuard صالحاً (HTTP $code)"
                                }
                            } catch (e: Exception) {
                                msg = "فشل التنزيل: ${e.message?.take(100)}"
                            }
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                downloading = false
                                statusMsg = msg
                            }
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { webRef = null; it.destroy() },
            modifier = Modifier.fillMaxSize().weight(1f)
        )
    }
}

private fun statText(name: String, st: Stat?): String =
    if (st == null) "$name: —" else "$name: ${st.median} ms ±${st.jitter} فقد ${st.loss}%"

private fun dnsMeasureLine(m: DnsMeasure): String {
    val parts = ArrayList<String>()
    parts += if (m.udp == null) "UDP: لا استجابة" else statText("UDP", m.udp)
    m.dot?.let { parts += statText("DoT", it) }
    m.doh?.let { parts += statText("DoH", it) }
    return parts.joinToString(" • ")
}

private fun validIp(v: String): Boolean {
    val x = v.trim()
    if (x.isEmpty()) return false
    val p = x.split(".")
    if (p.size == 4 && p.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) return true
    return x.contains(":") && x.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
}

@Composable
fun DnsTab(app: WgApp) {
    val list by app.store.dns.collectAsState()
    val s by app.store.settings.collectAsState()
    val res by app.engine.dnsResults.collectAsState()
    val st by app.engine.status.collectAsState()
    val ni by app.engine.netInfo.collectAsState()
    val chosenDns = if (st.connected) st.activeDnsId else ni.direct?.bestDnsId
    var showAdd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) { Text("إضافة", maxLines = 1) }
            OutlinedButton(onClick = { app.engine.testDnsNow() }, modifier = Modifier.weight(1f)) { Text("اختبار", maxLines = 1) }
            OutlinedButton(onClick = { app.store.restoreDefaultDns() }, modifier = Modifier.weight(1f)) { Text("الافتراضية", maxLines = 1) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!s.autoDns) {
                            RadioButton(
                                selected = s.selDnsId == d.id,
                                onClick = { app.store.updateSettings { it.copy(selDnsId = d.id) } }
                            )
                        }
                        MarkerSlot(!chosenDns.isNullOrEmpty() && chosenDns == d.id)
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.Bold)
                            Text(d.ips().joinToString("  "), fontSize = 12.sp)
                            if (d.dot.isNotEmpty()) Text("DoT: ${d.dot}", fontSize = 11.sp)
                            if (d.filtered) {
                                Text(
                                    "مُرشِّح (يحجب مواقع) — لا يُختار تلقائياً إلا بإذن من الإعدادات",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                                )
                            }
                            res[d.id]?.let { m -> Text(dnsMeasureLine(m), fontSize = 12.sp) }
                        }
                        Switch(
                            checked = d.enabled,
                            onCheckedChange = { v ->
                                app.store.updateDns { l -> l.map { if (it.id == d.id) it.copy(enabled = v) else it } }
                            }
                        )
                        TextButton(onClick = { app.store.updateDns { l -> l.filter { it.id != d.id } } }) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var p3 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("إضافة خادم DNS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(p1, { p1 = it }, label = { Text("العنوان الأساسي") }, singleLine = true)
                    OutlinedTextField(p2, { p2 = it }, label = { Text("العنوان الثانوي (اختياري)") }, singleLine = true)
                    OutlinedTextField(p3, { p3 = it }, label = { Text("مضيف DoT (اختياري، للتثبيت بالروت)") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!validIp(p1) || (p2.isNotBlank() && !validIp(p2))) {
                        err = "عنوان IP غير صالح"
                    } else {
                        app.store.updateDns { it + DnsServer(name = name.ifBlank { p1.trim() }, primary = p1.trim(), secondary = p2.trim(), dot = p3.trim()) }
                        showAdd = false
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> Picker(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $current", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, t) ->
                DropdownMenuItem(text = { Text(t) }, onClick = { onPick(v); open = false })
            }
        }
    }
}

@Composable
fun SettingsTab(app: WgApp, a: Actions) {
    val s by app.store.settings.collectAsState()
    fun upd(f: (AppSettings) -> AppSettings) = app.store.updateSettings(f)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("الاختيار التلقائي", fontWeight = FontWeight.Bold)
        SwitchRow("اختيار أفضل كونفيج تلقائياً", s.autoConfig) { v -> upd { it.copy(autoConfig = v) } }
        SwitchRow("اختيار أفضل DNS تلقائياً", s.autoDns) { v -> upd { it.copy(autoDns = v) } }
        SwitchRow("السماح باختيار DNS المُرشِّحة (تحجب مواقع)", s.allowFilteredDns) { v ->
            upd { it.copy(allowFilteredDns = v) }
        }
        SwitchRow("فحص MTU المسار تلقائياً", s.autoMtu) { v -> upd { it.copy(autoMtu = v) } }
        Text(
            "عند إيقاف الاختيار التلقائي لكونفيج أو DNS يظهر زر اختيار بجانب كل عنصر في تبويبه.",
            fontSize = 12.sp
        )
        if (!s.autoMtu) {
            Picker(
                "MTU", s.selMtu.toString(),
                MtuCatalog.values.map { it to MtuCatalog.label(it) }
            ) { v -> upd { it.copy(selMtu = v) } }
        }

        Text("الفحص في الخلفية", fontWeight = FontWeight.Bold)
        SwitchRow("فحص DNS وMTU تلقائياً بدون تشغيل VPN", s.backgroundScan) { v ->
            upd { it.copy(backgroundScan = v) }
            a.syncService()
        }
        Text("يعمل عند فتح التطبيق وعند تبدّل الشبكة، ويعرض أفضل DNS وMTU للشبكة الحالية.", fontSize = 12.sp)

        Text("إعادة الاختيار (الوضع التلقائي فقط — اليدوي يشغّل ويفصل بيدك دائماً)", fontWeight = FontWeight.Bold)
        SwitchRow("عند تبدّل الشبكة أو تغيّر IP", s.reselectOnNetwork) { v -> upd { it.copy(reselectOnNetwork = v) } }
        Picker(
            "إعادة الفحص الكاملة دورياً", if (s.periodMin == 0) "إيقاف" else "كل ${s.periodMin} دقيقة",
            listOf(0 to "إيقاف", 15 to "كل 15 دقيقة", 30 to "كل 30 دقيقة", 60 to "كل 60 دقيقة", 120 to "كل 120 دقيقة")
        ) { v -> upd { it.copy(periodMin = v) } }

        Text("متقدم", fontWeight = FontWeight.Bold)
        SwitchRow("استخدام الروت لفحص MTU (su)", s.useRoot) { v -> upd { it.copy(useRoot = v) } }
        SwitchRow("تثبيت القيم المثلى بالروت", s.applyRoot) { v ->
            upd { it.copy(applyRoot = v) }
            if (!v) a.restoreSystem()
        }
        Text(
            "يخفض MTU واجهة أي VPN خارجي إلى أكبر حزمة تمر فعلاً. ولا يغيّر DNS النظام (Private DNS) " +
                "إلا لخادم DoT مقاس بفقد 0% وأفضل بوضوح من الحالي، ثم يتحقق من الحل بعد التطبيق ويتراجع تلقائياً إن ساء.",
            fontSize = 12.sp
        )
        OutlinedButton(onClick = a.restoreSystem, modifier = Modifier.fillMaxWidth()) {
            Text("استعادة إعداد DNS الخاص الأصلي للنظام")
        }
        OutlinedButton(onClick = a.batteryExempt, modifier = Modifier.fillMaxWidth()) {
            Text("استثناء التطبيق من توفير الطاقة")
        }
        SwitchRow("حذف الكونفيجات المنتهية تلقائياً عند الفتح", s.autoPurgeExpired) { v ->
            upd { it.copy(autoPurgeExpired = v) }
        }
        Text(
            "كونفيجات VPNBook تنتهي بعد 7 أيام — تُحذف وحدها عند فتح التطبيق (ولّد بديلاً من زر VPNBook).",
            fontSize = 12.sp
        )
        SwitchRow("حذف سيرفرات VPN الميتة تلقائياً", s.autoPurgeDeadOvpn) { v ->
            upd { it.copy(autoPurgeDeadOvpn = v) }
        }
        Text(
            "يحذف أي سيرفر ثبت فشله 3 مرات متتالية (فحص/اتصال حقيقي) أو غاب 7 أيام عن قائمة VPNGate — بدل تراكم المئات. لا يحذف النشط حالياً أبداً.",
            fontSize = 12.sp
        )
        Picker(
            "تثبيت WARP على دولة",
            when (s.warpCountry) {
                "DE" -> "🇩🇪 ألمانيا"
                "FR" -> "فرنسا"
                "NL" -> "هولندا"
                else -> "أي أجنبي (غير سوريا)"
            },
            listOf("" to "أي أجنبي (غير سوريا)", "DE" to "ألمانيا", "FR" to "فرنسا", "NL" to "هولندا")
        ) { v -> upd { it.copy(warpCountry = v) } }
        Text(
            "WARP يخرج من أقرب مركز Cloudflare — التثبيت يرفض السوري دائماً ويفضّل الدولة المختارة عند توفرها.",
            fontSize = 12.sp
        )
        Text(
            "MTU النفق = أقصى حزمة تصل للخادم − 60 بايت (IPv4) أو − 80 بايت (IPv6).",
            fontSize = 12.sp
        )

        HorizontalDivider()
        Text("قاطع الطوارئ (Kill Switch)", fontWeight = FontWeight.Bold)
        SwitchRow("منع أي إنترنت خارج النفق (يتطلب روت)", s.killSwitch) { v -> upd { it.copy(killSwitch = v) } }
        Text(
            "يحجب كل حركة الشبكة إلا عبر واجهة النفق (tun+) طالما التطبيق يعمل. إن سقط النفق يبقى الإنترنت مقطوعاً " +
                "بدل تسريب IP الحقيقي، حتى يعود النفق أو تضغط إيقاف. لا يعمل بلا روت.",
            fontSize = 12.sp
        )
        OutlinedButton(onClick = a.openVpnSettings, modifier = Modifier.fillMaxWidth()) {
            Text("فتح إعدادات Always-on VPN بالنظام")
        }
        Text(
            "إضافياً، تفعيل \"Always-on VPN\" و\"Block connections without VPN\" لهذا التطبيق من إعدادات أندرويد " +
                "يمنع أي تسريب حتى بلا روت، ويبقى فعّالاً حتى لو أُغلق التطبيق تماماً.",
            fontSize = 12.sp
        )

        val ctx = LocalContext.current
        val ver = remember {
            try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        HorizontalDivider()
        var verTaps by remember { mutableIntStateOf(0) }
        var showPrompt by remember { mutableStateOf(false) }
        var toolUnlocked by remember { mutableStateOf(false) }
        Text(
            "حول التطبيق", fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                verTaps++
                if (verTaps >= 7) {
                    showPrompt = true
                    verTaps = 0
                }
            }
        )
        Text("الإصدار: $ver")
        Text("Star Syria")
        TextButton(onClick = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:starsyria2500@gmail.com")))
            } catch (e: Exception) {
                // لا يوجد تطبيق بريد
            }
        }) { Text("📧 starsyria2500@gmail.com") }
        TextButton(onClick = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/963938466549")))
            } catch (e: Exception) {
                // لا يوجد واتساب مثبَّت
            }
        }) { Text("💬 واتساب: 963938466549+") }
        // TODO: أضف رابط قناة تلغرام هنا فور تزويدي به، بنفس نمط الزرّين أعلاه:
        // TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/USERNAME"))) }) { Text("📣 قناة تلغرام") }

        val lic = License.status(app.store)
        Text(
            when {
                lic.isOwner -> "الترخيص: دخول مالك"
                lic.expiryAt > 0 ->
                    "الترخيص: ساري حتى " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(lic.expiryAt))
                else -> "الترخيص: غير مفعّل"
            },
            fontSize = 12.sp
        )
        if (showPrompt) {
            if (toolUnlocked) {
                OwnerToolPanel(app)
                OutlinedButton(onClick = {
                    toolUnlocked = false
                    showPrompt = false
                }, modifier = Modifier.fillMaxWidth()) { Text("خروج من الأداة") }
            } else {
                var phrase by remember { mutableStateOf("") }
                var lm by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = phrase, onValueChange = { phrase = it },
                    label = { Text("عبارة دخول المالك") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    lm = when (val r = License.verifyOwnerAttempt(app.store, phrase)) {
                        is License.Attempt.Ok -> { toolUnlocked = true; "" }
                        is License.Attempt.Wrong -> "عبارة غير صحيحة"
                        is License.Attempt.Locked -> "محاولات كثيرة — انتظر ${r.secondsLeft} ثانية"
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("دخول") }
                if (lm.isNotEmpty()) Text(lm, fontSize = 12.sp)
            }
        }
    }
}

/** أداة توليد أكواد التفعيل داخل التطبيق — تظهر فقط بعد دخول المالك، بلا أي أوامر يدوية خارجية. */
@Composable
fun OwnerToolPanel(app: WgApp) {
    var name by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("365") }
    var permanent by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }
    var err by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(License.historyList(app.store)) }
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)

    fun copy(text: String) {
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("code", text))
    }

    HorizontalDivider()
    Text("أداة توليد أكواد التفعيل", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("اسم المستخدم (للسجل فقط)") }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = device, onValueChange = { device = it; err = "" },
        label = { Text("معرّف جهاز المستخدم") }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = days, onValueChange = { days = it.filter { c -> c.isDigit() } },
            label = { Text("عدد الأيام") }, singleLine = true, enabled = !permanent,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text("دائم", fontSize = 13.sp)
        Switch(checked = permanent, onCheckedChange = { permanent = it })
    }
    Button(onClick = {
        val d = device.trim()
        if (d.isEmpty()) {
            err = "أدخل معرّف الجهاز أولاً"
            return@Button
        }
        val daysNum = if (permanent) 0 else (days.toIntOrNull() ?: 0)
        val (code, expiry) = License.generateCodeForOwner(d, daysNum)
        result = code to expiry
        License.historyAdd(
            app.store,
            License.IssuedCode(
                name.trim(), d.replace("-", "").uppercase(), code, expiry,
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            )
        )
        history = License.historyList(app.store)
        copy(code)
    }, modifier = Modifier.fillMaxWidth()) { Text("توليد الكود") }
    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

    result?.let { (code, expiry) ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("الكود (نُسخ تلقائياً)", fontSize = 12.sp)
                Text(code, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("صالح حتى: $expiry", fontSize = 12.sp)
                OutlinedButton(onClick = { copy(code) }, modifier = Modifier.fillMaxWidth()) { Text("نسخ مرة أخرى") }
            }
        }
    }

    if (history.isNotEmpty()) {
        Text("السجل (الأحدث أولاً)", fontWeight = FontWeight.Bold)
        history.asReversed().take(30).forEach { h ->
            Card(Modifier.fillMaxWidth().clickable { copy(h.code) }) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        (if (h.name.isNotEmpty()) "${h.name} — " else "") + h.deviceId,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    Text("${h.code}  •  حتى ${h.expiry}  •  صدر ${h.issuedAt}", fontSize = 12.sp)
                }
            }
        }
    }
}

// =====================================================================
// تبويب VPN 🌍 — سيرفرات OpenVPN مجانية من VPNGate تتحدث تلقائياً.
// الفحص بنفس معادلة الامتحان الموحدة (الزمن 50% + الفقد 30% + التذبذب 20%)،
// والاتصال يتم عبر تطبيق OpenVPN خارجي بعد إيقاف نفق WireGuard (نفق واحد فقط
// مسموح في أندرويد). DNS وMTU يُقترحان من فحص شبكتك، وكونفيج VPNGate يدفع
// DNS الخاص به عند الاتصال.
// =====================================================================

/** يجلب القائمة من VPNGate ويدمجها مع الكاش (يحتفظ بسيرفرات اختفت منذ أقل من 48 ساعة
 *  لتنويع الدول في الأيام التي تطغى فيها دولة واحدة). يعيد رسالة عرض. */
suspend fun refreshOvpnServers(app: WgApp): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val csv = VpnGate.fetchCsv()
        ?: return@withContext "تعذّر الجلب — تحقق من الإنترنت (الكاش القديم ما زال يعمل)"
    val parsed = VpnGate.parse(csv)
    if (parsed.isEmpty()) return@withContext "رد فارغ من VPNGate — حاول لاحقاً"
    app.store.saveVpnGateCsv(csv)
    val now = System.currentTimeMillis()
    val old = app.store.ovpn.value.associateBy { it.key }
    val freshKeys = parsed.map { it.key }.toSet()
    // القدامى الغائبون منذ أقل من 48 ساعة يبقون (موسومون ضمنياً بقدم lastSeen)
    // سيرفرات VPNBook (vbk-*) ليست من VPNGate أصلاً — تبقى صالحة دائماً ولا تُوسم قديمة.
    val kept = old.values.filter { it.key !in freshKeys && now - it.lastSeen < 48L * 3600 * 1000 }
    app.store.updateOvpn {
        parsed.map { s ->
            val o = old[s.key]
            val tcpCapable = o?.tcp ?: run {
                try {
                    VpnGate.decodeConfig(s.configB64)?.let { VpnGate.hasProto(it, "tcp") } ?: true
                } catch (e: Exception) { true }
            }
            OvpnServer(
                s.key, s.host, s.ip, s.country, s.sessions, s.speedMbps, s.pingAdvertised,
                lat = o?.lat ?: -1, jitter = o?.jitter ?: 0, loss = o?.loss ?: 100,
                via = o?.via ?: "", testedAt = o?.testedAt ?: 0L, lastSeen = now, stale = false,
                tcp = tcpCapable, fails = o?.fails ?: 0
            )
        } + kept.map {
            // VPNBook صالح بملفه المحلي — لا يُعطَّل بتحديث قائمة VPNGate
            if (it.key.startsWith("vbk-")) it.copy(lastSeen = now, stale = false)
            else it.copy(stale = true)
        }
    }
    app.store.setOvpnUpdatedAt(now)
    val purged = if (app.store.settings.value.autoPurgeDeadOvpn) app.store.purgeDeadOvpn() else 0
    val nCountries = (parsed.map { it.country } + kept.map { it.country }).distinct().size
    "تم التحديث: ${parsed.size + kept.size} سيرفراً في $nCountries دولة" + if (purged > 0) " • حُذف $purged ميتاً تلقائياً" else ""
}

/** يفحص أفضل المرشحين ويحفظ النتائج تدريجياً. يعيد المرتبين (الأفضل أولاً). */
suspend fun examOvpnBest(
    app: WgApp,
    prefer: String,
    onProgress: (String) -> Unit
): List<OvpnServer> {
    val csv = app.store.loadVpnGateCsv() ?: return emptyList()
    val banned = app.store.ovpn.value
        .filter { VpnGate.connectFailedRecently(it) }
        .map { it.key }.toSet()
    val cands = VpnGate.candidates(VpnGate.parse(csv), prefer)
        .filterNot { it.key in banned }
    if (cands.isEmpty()) return emptyList()
    onProgress("فحص ${cands.size} مرشحاً…")
    val now = System.currentTimeMillis()
    VpnGate.examMany(cands, prefer) { key, r ->
        if (r != null) VpnGate.markOk(app.store, key, r)
    }
    // سيرفرات VPNBook المحلية (vbk-*) تُفحص أيضاً لتترشح للأفضل مثل VPNGate
    val vbk = app.store.ovpn.value.filter { it.key.startsWith("vbk-") && !it.stale }
    if (vbk.isNotEmpty()) {
        onProgress("فحص سيرفرات VPNBook…")
        vbk.forEach { v ->
            val r = try { VpnGate.examStored(app.store, v.key, prefer) } catch (e: Exception) { null }
            if (r != null) {
                if (r.loss >= 100) VpnGate.bumpFail(app.store, v.key, r.via.ifEmpty { "لا استجابة" })
                else VpnGate.markOk(app.store, v.key, r)
            } else {
                VpnGate.bumpFail(app.store, v.key, "لا استجابة")
            }
        }
    }
    onProgress("")
    return app.store.ovpn.value
        .filter { it.tested && it.loss < 100 && !it.stale && it.sessions < 200 }
        .sortedWith(
            if (prefer == "auto") compareBy(
                { if (it.via.startsWith("TCP")) 0 else 1 },
                { if (it.tcp) 0 else 1 }, { it.score }, { it.sessions }
            )
            else compareBy({ it.score }, { it.sessions })
        )
}

/**
 * اتصال تلقائي ذكي: يفحص المرشحين ثم يجرّب أفضل 3 بالتتابع حتى ينجح واحد.
 * (سيرفرات المتطوعين تموت فجأة — المحاولة الواحدة كانت سبب «الضعف» الظاهر.)
 * يعيد رسالة النتيجة النهائية للعرض.
 */
suspend fun autoConnectOvpnBest(
    app: WgApp, prefer: String, onProgress: (String) -> Unit
): String = withContext(kotlinx.coroutines.Dispatchers.Main) {
    onProgress("فحص المرشحين…")
    val ranked = withContext(kotlinx.coroutines.Dispatchers.IO) {
        examOvpnBest(app, prefer) {}
    }
    if (ranked.isEmpty()) {
        onProgress("")
        return@withContext "لا سيرفر يستجيب الآن — حدّث القائمة أو جرّب TCP ثم أعد"
    }
    val tried = ArrayList<String>()
    for (s in ranked.take(3)) {
        onProgress("محاولة ${tried.size + 1}/3: ${s.host}…")
        val immediate = startOvpnInternal(app, s, prefer)
        // فشل فوري (قديم/كلمة سر/تجهيز) — جرّب التالي بلا انتظار
        if (!app.ovpn.state.value.connecting && !app.ovpn.state.value.active) {
            tried += "${s.host} ($immediate)"
            continue
        }
        // انتظار النتيجة حتى 30 ثانية: متصل = نجاح، سقوط = التالي
        var waited = 0
        var done: Boolean? = null
        while (waited < 30) {
            kotlinx.coroutines.delay(1000)
            waited++
            val st = app.ovpn.state.value
            if (st.active) {
                done = true
                break
            }
            if (!st.connecting && !st.active && st.serverKey.isEmpty()) {
                done = false
                break
            }
        }
        when (done) {
            true -> {
                onProgress("")
                return@withContext "✔ متصل عبر ${s.host}" +
                    if (tried.isNotEmpty()) " (بعد فشل: ${tried.joinToString("؛ ")} )" else ""
            }
            false -> {
                tried += "${s.host} (سقط الاتصال — استُبعد تلقائياً)"
                continue
            }
            else -> {
                app.ovpn.stop()
                tried += "${s.host} (انتهت المهلة 30ث)"
            }
        }
    }
    onProgress("")
    "تعذّر الثلاثة الأفضل — ${tried.joinToString("؛ ")} — جرّب سيرفراً يدوياً أو غيّر البروتوكول"
}

/**
 * يتصل بسيرفر OpenVPN داخل التطبيق: يوقف WireGuard أولاً (نفق واحد فقط)،
 * ثم يشغّل الكونفيج المفلتر حسب تفضيل البروتوكول عبر المحرك المدمج. يعيد رسالة عرض.
 */
fun startOvpnInternal(app: WgApp, server: OvpnServer, prefer: String): String {
    if (server.stale)
        return "هذا السيرفر غاب عن آخر تحديث — اختر سيرفراً غير موسوم بـ (قديم)"
    if (app.engine.status.value.running) app.engine.stop()
    if (app.ovpn.isUp()) app.ovpn.stop()
    val authPath = java.io.File(app.cacheDir, "ovpn/auth.txt").absolutePath
    val isVbk = server.key.startsWith("vbk-")
    val user = if (isVbk) VpnBook.OVPN_USER else "vpn"
    val pass = if (isVbk) (app.store.prefGet("vpnbookPass") ?: "") else "vpn"
    if (isVbk && pass.isEmpty())
        return "انتهت كلمة سر VPNBook — أعد توليد سيرفر من زر VPNBook لتحديثها"
    val ready = VpnGate.buildConnectConfig(app.store, server.key, prefer, authPath, user, pass)
        ?: return "تعذّر تجهيز هذا السيرفر — حدّث القائمة أو اختر سيرفراً آخر"
    // الاعتماد عبر وسيطي النواة + سطر عارٍ (ملف auth-user-pass الخارجي تتجاهله النواة)
    return app.ovpn.start(server, VpnGate.stripAuthPath(ready), user, pass)
}

@Composable
fun OvpnTab(app: WgApp, a: Actions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by app.store.ovpn.collectAsState()
    val updatedAt by app.store.ovpnUpdatedAt.collectAsState()
    val settings by app.store.settings.collectAsState()
    val ni by app.engine.netInfo.collectAsState()
    val wgSt by app.engine.status.collectAsState()
    val ovSt by app.ovpn.state.collectAsState()
    var busy by remember { mutableStateOf("") }
    var countryFilter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
    val prefer = settings.ovpnProto

    var webVBook by remember { mutableStateOf(false) }
    if (webVBook) {
        VpnBookWebView(
            app = app,
            title = "مولد VPNBook-OpenVPN داخل التطبيق",
            url = VpnBook.OVPN_URL,
            namePrefix = "vbk",
            hint = "اختر سيرفراً قريباً (ألمانيا/فرنسا/بريطانيا) وبروتوكول TCP 443 — الملف يُستورد كسيرفر تلقائياً",
            onClose = { webVBook = false },
            expectOvpn = true,
            onOvpn = { text, name ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var msg: String
                    var addedKey: String? = null
                    try {
                        val rems = VpnGate.remotes(text)
                        if (rems.isEmpty()) {
                            msg = "الكونفيج بلا سيرفر remote — اختر ملفاً آخر"
                        } else {
                            val host = rems.first().host
                            val cc = VpnBook.displayCountry(host, "", name).ifBlank { VpnBook.countryOfName(text.take(2000)) }
                            val key = "vbk-" + host.lowercase().replace(Regex("[^a-z0-9.-]"), "_")
                            app.store.saveVpnBookOvpn(key, text)
                            val tcpCap = rems.any { it.proto == "tcp" }
                            app.store.updateOvpn { l ->
                                val rest = l.filter { it.key != key }
                                rest + OvpnServer(
                                    key, host, host, cc, 0, 0.0, -1,
                                    lastSeen = System.currentTimeMillis(), stale = false, tcp = tcpCap
                                )
                            }
                            addedKey = key
                            val pwd = VpnBook.fetchPassword() ?: app.store.prefGet("vpnbookPass")
                            if (!pwd.isNullOrEmpty()) app.store.prefPut("vpnbookPass", pwd)
                            val ccLabel = if (cc.isBlank()) "VPNBook" else "${Country.flag(cc)} $cc • ${Country.name(cc)}"
                            msg = if (pwd.isNullOrEmpty())
                                "✔ أُضيف $host ($ccLabel) — تعذّر جلب كلمة السر، سيُطلب التحديث عند الاتصال"
                            else "✔ أُضيف $host ($ccLabel) كسيرفر — افحصه واتصل"
                        }
                    } catch (e: Exception) {
                        msg = "فشل تجهيز السيرفر: ${e.message?.take(100)}"
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        toast(msg)
                        // إظهار مضمون: رجوع تلقائي للقائمة مع تصفير الفلاتر حتى لا يختفي السيرفر
                        if (addedKey != null) {
                            countryFilter = "ALL"
                            query = ""
                            webVBook = false
                        }
                    }
                }
            }
        )
        return
    }

    // إصلاح فوري لسيرفرات VPNBook القديمة بلا دولة: نستدل من IP/الاسم مرة واحدة
    LaunchedEffect(Unit) {
        val needFix = app.store.ovpn.value.filter { it.key.startsWith("vbk-") && it.country.isBlank() }
        if (needFix.isNotEmpty()) {
            app.store.updateOvpn { l ->
                l.map { s ->
                    if (s.key.startsWith("vbk-") && s.country.isBlank()) {
                        val cc = VpnBook.displayCountry(s.host, "")
                        if (cc.isNotBlank()) s.copy(country = cc) else s
                    } else s
                }
            }
        }
        if (app.store.settings.value.autoPurgeDeadOvpn) {
            val n = app.store.purgeDeadOvpn()
            if (n > 0) toast("حُذف $n سيرفراً ميتاً تلقائياً (فشل مثبت أو غياب طويل)")
        }
        if (servers.isEmpty()) {
            app.store.loadVpnGateCsv()?.let { csv ->
                val list = VpnGate.parse(csv)
                if (list.isNotEmpty()) {
                    app.store.updateOvpn { _ ->
                        list.map { s ->
                            OvpnServer(s.key, s.host, s.ip, s.country, s.sessions, s.speedMbps, s.pingAdvertised)
                        }
                    }
                }
            }
        }
        if (System.currentTimeMillis() - app.store.ovpnUpdatedAt.value > VpnGate.STALE_MS) {
            busy = "تحديث تلقائي للقائمة…"
            val msg = refreshOvpnServers(app)
            busy = ""
            if (app.store.ovpn.value.isNotEmpty()) toast(msg)
        }
    }

    val ageText = if (updatedAt <= 0L) "لم تُحدَّث بعد" else {
        val age = System.currentTimeMillis() - updatedAt
        when {
            age < 60_000 -> "حُدّثت قبل لحظات"
            age < 3_600_000 -> "حُدّثت قبل ${age / 60_000} دقيقة"
            else -> "حُدّثت قبل ${age / 3_600_000} ساعة"
        }
    }
    // الممتلئ (200+ جلسة) مستبعد من الترشيح — والTCP أولاً في الوضع التلقائي
    val tested = servers.filter { it.tested && it.loss < 100 && !it.stale && it.sessions < 200 }
        .sortedWith(
            if (prefer == "auto") compareBy({ if (it.tcp) 0 else 1 }, { it.score }, { it.sessions })
            else compareBy({ it.score }, { it.sessions })
        )
    val best = tested.firstOrNull()
    val topList = tested.take(3)
    val topKeys = topList.map { it.key }.toSet()
    fun rankOf(key: String) = topList.indexOfFirst { it.key == key }
    val countries = servers.map { VpnBook.displayCountry(it.host, it.country).ifBlank { it.country } }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = servers.filter {
        val dc = VpnBook.displayCountry(it.host, it.country)
        (countryFilter == "ALL" || dc == countryFilter || it.country == countryFilter) &&
            (query.isBlank() || it.host.contains(query.trim(), true) || it.ip.contains(query.trim()))
    }.sortedWith(compareBy({ val r = rankOf(it.key); if (r >= 0) r else 99 }, { it.score }))

    // ألوان ثابتة في الفاتح والداكن — قبل LazyColumn لأن isSystemInDarkTheme استدعاء @Composable
    val dark = isSystemInDarkTheme()
    val okBg = if (dark) Color(0xFF12351F) else Color(0xFFE8F5E9)
    val warnBg = if (dark) Color(0xFF3A2E00) else Color(0xFFFFF8E1)
    val infoBg = if (dark) Color(0xFF0F2B4A) else Color(0xFFE3F2FD)
    val statusFg = if (dark) Color.White else Color(0xFF111111)

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wgUp = wgSt.running && wgSt.connected
        // ---- ترويسة مميزة للتبويب ----
        item {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B3F73), contentColor = Color.White)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🌍", fontSize = 30.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "سيرفرات OpenVPN المجانية",
                        fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = Color.White
                    )
                    Text(
                        "تتحدث تلقائياً من VPNGate • بجانب WireGuard وليس بديلاً عنه",
                        fontSize = 12.sp, color = Color(0xFFD6E4FF)
                    )
                }
            }
        }
        }
        // ---- شريط الحالة: هل يوجد اتصال؟ وهل يمكن فصله؟ ----
        item {
        when {
            ovSt.active -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = okBg, contentColor = statusFg)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🟢 متصل عبر OpenVPN داخل التطبيق", fontWeight = FontWeight.Bold)
                    if (ovSt.serverLabel.isNotEmpty()) Text(ovSt.serverLabel, fontSize = 13.sp)
                    Text("النفق يعمل الآن — افصله من هنا بضغطة واحدة", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { app.engine.scanNetwork("فحص يدوي من تبويب VPN") },
                            modifier = Modifier.weight(1f)
                        ) { Text("فحص", maxLines = 1) }
                        Button(
                            onClick = { toast(app.ovpn.stop()) },
                            modifier = Modifier.weight(1f)
                        ) { Text("فصل ⏻", maxLines = 1) }
                    }
                }
            }
            ovSt.connecting -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = warnBg, contentColor = statusFg)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🟡 ${ovSt.statusText.ifEmpty { "جارٍ الاتصال…" }}", fontWeight = FontWeight.Bold)
                    if (ovSt.serverLabel.isNotEmpty()) Text(ovSt.serverLabel, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = { toast(app.ovpn.stop()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("إلغاء — إغلاق VPN") }
                }
            }
            wgUp -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = infoBg, contentColor = statusFg)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🔵 متصل عبر WireGuard (${wgSt.activeConfig ?: ""})", fontWeight = FontWeight.Bold)
                    Text("أوقف WireGuard أولاً قبل تشغيل OpenVPN (نفق واحد فقط مسموح)", fontSize = 12.sp)
                    OutlinedButton(
                        onClick = { app.engine.stop(); toast("أُوقف WireGuard — اختر سيرفر OpenVPN الآن") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("إيقاف WireGuard") }
                }
            }
            ni.vpn != null -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("🟡 يوجد نفق — يُفحص الآن…", fontSize = 13.sp)
                }
            }
            else -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("⚪ لا يوجد اتصال VPN حالياً", fontSize = 13.sp)
                }
            }
        }
        }

        // ---- زر واحد: افحص واتصل بالأفضل تلقائياً ----
        item {
        Button(
            onClick = {
                scope.launch {
                    busy = "فحص المرشحين…"
                    val msg = autoConnectOvpnBest(app, prefer) { pr -> busy = pr }
                    busy = ""
                    toast(msg)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("⚡ فحص واتصال تلقائي بالأسرع (يجرب 3)") }
        }

        // ---- مولد VPNBook-OpenVPN (سيرفرات قريبة وسريعة) ----
        item {
        OutlinedButton(
            onClick = { webVBook = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("📖 توليد VPNBook-OpenVPN داخل التطبيق (أمريكا/أوروبا)") }
        }

        // ---- القائمة والتحديث ----
        item {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        busy = "جلب القائمة…"
                        toast(refreshOvpnServers(app))
                        busy = ""
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("تحديث السيرفرات", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = "فحص المرشحين…"
                        val res = examOvpnBest(app, prefer) { p -> busy = p }
                        busy = ""
                        toast(if (res.isEmpty()) "لا استجابة من المرشحين — جرّب TCP" else "الأفضل: ${res.first().host} (${res.first().lat}ms)")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("فحص السرعة", maxLines = 1) }
        }
        }
        item {
        Text(
            "$ageText • ${servers.size} سيرفراً في ${countries.size} دولة • المصدر: VPNGate مجاني",
            fontSize = 11.sp
        )
        }

        // ---- تفضيل البروتوكول: شرائح واضحة (يدوي أو تلقائي) ----
        item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("البروتوكول", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val opts = listOf(
                        "auto" to "⚡ تلقائي",
                        "udp" to "🚀 UDP أسرع",
                        "tcp" to "🧱 TCP للحجب"
                    )
                    opts.forEach { (v, t) ->
                        FilterChip(
                            selected = prefer == v,
                            onClick = { app.store.updateSettings { it.copy(ovpnProto = v) } },
                            label = { Text(t, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    when (prefer) {
                        "udp" -> "UDP: سرعة أعلى وزمن أقل — الأفضل للتصفح والمكالمات."
                        "tcp" -> "TCP: يعبر معظم الجدران والشبكات المقيدة (443/80) — أبطأ قليلاً."
                        else -> "تلقائي: يفحص TCP أولاً ثم Ping، ويختار أعلى درجة بالمعادلة الموحدة."
                    },
                    fontSize = 11.sp
                )
            }
        }
        }

        // ---- ثبات الاتصال: البطارية + إحياء تلقائي ----
        item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🛡 ثبات الاتصال", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "أندرويد يقتل التطبيقات في الخلفية لتوفير البطارية/الذاكرة. خطوتان تمنعان الفصل بعد دقائق:",
                    fontSize = 11.sp
                )
                OutlinedButton(
                    onClick = { a.batteryExempt() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("استثناء التطبيق من توفير البطارية") }
                SwitchRow("إعادة الوصل تلقائياً إن أسقطه النظام", settings.ovpnAutoReconnect) { v ->
                    app.store.updateSettings { it.copy(ovpnAutoReconnect = v) }
                }
                Text(
                    "نصيحة للجهاز: اقفل التطبيق في شاشة التطبيقات الأخيرة (القفل 🔒) وفعّل التشغيل التلقائي من إعدادات البطارية.",
                    fontSize = 11.sp
                )
            }
        }
        }

        // ---- الاختيار التلقائي: الأفضل بضغطة (بطاقة خضراء مميزة) ----
        if (best != null) {
            item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🥇", fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        val bcc = VpnBook.displayCountry(best.host, best.country)
                        val bPrefix = if (VpnBook.isBookKey(best.key)) "📖 " else ""
                        Text("الأفضل تلقائياً — جاهز للاتصال" + if (bPrefix.isNotEmpty()) " • VPNBook" else "", fontSize = 11.sp, color = Color(0xFFC8E6C9))
                        Text(
                            "$bPrefix${Country.flag(bcc)} $bcc • ${best.host}",
                            fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White
                        )
                        Text(
                            "${Country.name(bcc)} ($bcc) • ${best.ip} • ${best.lat}ms • فقد ${best.loss}% • ${VpnGate.speedText(best.speedMbps)}",
                            fontSize = 12.sp, color = Color(0xFFE8F5E9)

                        )
                    }
                    Button(
                        onClick = { a.startOvpn(best) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White, contentColor = Color(0xFF2E7D32)
                        )
                    ) { Text("اتصال", fontWeight = FontWeight.Bold) }
                }
            }
            }
        }

        // ---- سيرفرات VPNBook المولّدة: قسم ثابت أعلى القائمة فلا تضيع بين المئات ----
        val vbkServers = servers.filter { it.key.startsWith("vbk-") && !it.stale }
        if (vbkServers.isNotEmpty()) {
            item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📖 سيرفرات VPNBook المولّدة (${vbkServers.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    vbkServers.forEach { v ->
                        val bannedV = VpnGate.connectFailedRecently(v)
                        val failedV = v.tested && v.loss >= 100
                        val okV = v.tested && v.loss < 100 && !bannedV
                        // تباين قوي يعمل في الوضعين الفاتح والداكن: أخضر مشبع = يعمل، أحمر مشبع = فاشل
                        val bgColor = when {
                            bannedV || failedV -> Color(0xFFC62828)
                            okV -> Color(0xFF2E7D32)
                            else -> null
                        }
                        val fgColor = Color.White
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = if (bgColor != null)
                                CardDefaults.cardColors(containerColor = bgColor)
                            else CardDefaults.cardColors()
                        ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    bannedV || failedV -> "🔴"
                                    okV -> "🟢"
                                    else -> "⚪"
                                },
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                val vcc = VpnBook.displayCountry(v.host, v.country)
                                Text(
                                    "${Country.flag(vcc)} $vcc • ${Country.name(vcc)} • ${v.host}",
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                                    color = if (bgColor != null) fgColor else Color.Unspecified
                                )
                                Text(
                                    when {
                                        bannedV -> "🚫 مستبعد: فشل اتصاله مؤخراً — يعود بعد 24 ساعة"
                                        failedV -> "🔴 فشل آخر فحص/اتصال — جرّب غيره"
                                        v.tested -> "🟢 " + VpnGate.examText(VpnGate.ExamResult(v.lat, v.jitter, v.loss, v.via))
                                        else -> "جديد — اضغط فحص ثم اتصال"
                                    },
                                    fontSize = 11.sp,
                                    color = if (bgColor != null) Color(0xFFF1F8E9) else Color.Unspecified
                                )
                            }
                            if (bgColor != null) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            busy = "فحص ${v.host}…"
                                            val r = VpnGate.examStored(app.store, v.key, prefer)
                                            if (r != null) {
                                                if (r.loss >= 100) VpnGate.bumpFail(app.store, v.key, r.via.ifEmpty { "لا استجابة" })
                                                else VpnGate.markOk(app.store, v.key, r)
                                                toast("${v.host}: ${r.lat}ms • فقد ${r.loss}%")
                                            } else {
                                                VpnGate.bumpFail(app.store, v.key, "لا استجابة")
                                                toast("${v.host}: لا استجابة — جرّب سيرفراً آخر")
                                            }
                                            busy = ""
                                        }
                                    }
                                ) { Text("فحص", maxLines = 1, color = fgColor) }
                                Button(
                                    onClick = { a.startOvpn(v) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = if (failedV || bannedV) Color(0xFFC62828) else Color(0xFF2E7D32)
                                    )
                                ) { Text("اتصال", maxLines = 1, fontWeight = FontWeight.Bold) }
                            } else {
                            TextButton(onClick = {
                                scope.launch {
                                    busy = "فحص ${v.host}…"
                                    val r = VpnGate.examStored(app.store, v.key, prefer)
                                    if (r != null) {
                                        if (r.loss >= 100) VpnGate.bumpFail(app.store, v.key, r.via.ifEmpty { "لا استجابة" })
                                        else VpnGate.markOk(app.store, v.key, r)
                                        toast("${v.host}: ${r.lat}ms • فقد ${r.loss}%")
                                    } else {
                                        VpnGate.bumpFail(app.store, v.key, "لا استجابة")
                                        toast("${v.host}: لا استجابة — جرّب سيرفراً آخر")
                                    }
                                    busy = ""
                                }
                            }) { Text("فحص", maxLines = 1) }
                            Button(onClick = { a.startOvpn(v) }) { Text("اتصال", maxLines = 1) }
                            }
                        }
                        }
                    }
                }
            }
            }
        }

        // ---- فلترة يدوية: شرائح الدول + بحث ----
        item { Text("الدولة", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        item {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = countryFilter == "ALL",
                onClick = { countryFilter = "ALL" },
                label = { Text("🌐 الكل (${servers.size})", maxLines = 1) }
            )
            countries.forEach { cc ->
                val n = servers.count { VpnBook.displayCountry(it.host, it.country) == cc }
                FilterChip(
                    selected = countryFilter == cc,
                    onClick = { countryFilter = cc },
                    label = { Text("${Country.flag(cc)} ${Country.name(cc)} ($n)", maxLines = 1) }
                )
            }
        }
        }
        item {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("بحث باسم السيرفر أو IP") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        }

        // ---- سجل OpenVPN الحي: تشخيص الاتصال داخل التطبيق ----
        item {
        val ovpnLog by app.ovpn.logLines.collectAsState()
        if (ovSt.connecting || ovSt.active || ovpnLog.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("سجل الاتصال المباشر", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    val tail = ovpnLog.takeLast(6)
                    if (tail.isEmpty()) Text("بانتظار بدء الاتصال…", fontSize = 12.sp)
                    else tail.forEach { line -> Text(line, fontSize = 11.sp) }
                }
            }
        }
        }

        // ---- DNS وMTU المقترحان من فحص شبكتك ----
        item {
        ni.direct?.let { d ->
            if (d.bestDns.isNotEmpty() || d.suggestedMtu > 0) {
                Text(
                    "مقترح شبكتك: DNS ${d.bestDns.ifEmpty { "—" }}${if (d.dnsMs >= 0) " (${d.dnsMs}ms)" else ""} • MTU ${if (d.suggestedMtu > 0) d.suggestedMtu else "—"} — كونفيج VPNGate يدفع DNS الخاص به عند الاتصال",
                    fontSize = 11.sp
                )
            }
        }
        }

        if (busy.isNotEmpty()) item { Text("⏳ $busy", fontSize = 12.sp, fontWeight = FontWeight.Bold) }

        // ---- القائمة اليدوية: علم + حالة + فحص + اتصال ----
        if (shown.isEmpty()) {
            item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("لا توجد سيرفرات بعد — اضغط «تحديث السيرفرات» لجلب القائمة المجانية", fontSize = 13.sp)
                }
            }
            }
        }
        items(shown, key = { it.key }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // سيرفرات VPNBook فقط: علم + رمز الدولة دائماً (حتى لو الكاش قديم)
                            val dcc = VpnBook.displayCountry(s.host, s.country)
                            Text("${Country.flag(dcc)}", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                val rank = rankOf(s.key)
                                val bookMark = if (VpnBook.isBookKey(s.key)) "📖 " else ""
                                Text(
                                    bookMark + (if (rank >= 0) "${Exam.rankMedal(rank)} " else "") + s.host,
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                                )
                                Text(
                                    (if (VpnBook.isBookKey(s.key)) "VPNBook $dcc • " else "") +
                                        "${Country.name(dcc)} • ${s.ip} • ${s.sessions} جلسة" +
                                        (if (s.sessions >= 200) " (ممتلئ — مستبعد)" else "") +
                                        (if (s.tcp) " • TCP" else " • UDP فقط") +
                                        " • ${VpnGate.speedText(s.speedMbps)}",
                                    fontSize = 12.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (!s.tested) "⚪" else if (s.loss >= 100) "🔴" else "🟢",
                                    fontSize = 18.sp
                                )
                                Text(
                                    if (!s.tested) "لم يُفحص" else if (s.loss >= 100) "لا يعمل" else "يعمل",
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Text(
                            if (s.tested) VpnGate.examText(VpnGate.ExamResult(s.lat, s.jitter, s.loss, s.via))
                            else if (s.pingAdvertised >= 0) "معلن: ${s.pingAdvertised}ms — لم يُفحص من جهازك"
                            else "لم يُفحص بعد",
                            fontSize = 12.sp
                        )
                        if (s.stale) {
                            Text(
                                "⏳ غاب عن آخر تحديث — لا يمكن اتصاله الآن (قد يعود غداً)",
                                fontSize = 12.sp
                            )
                        }
                        if (VpnGate.connectFailedRecently(s)) {
                            Text(
                                "🚫 مستبعد تلقائياً: فشل اتصاله الفعلي مؤخراً (${s.via}) — يُعاد فحصه بعد 24 ساعة",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (!s.stale) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        busy = "فحص ${s.host}…"
                                        val cur = app.store.ovpn.value.firstOrNull { it.key == s.key }
                                        if (cur != null && VpnGate.connectFailedRecently(cur)) {
                                            toast("${s.host}: مستبعد مؤقتاً (فشل اتصاله مؤخراً) — يعود للفحص تلقائياً بعد 24 ساعة")
                                            busy = ""
                                            return@launch
                                        }
                                        val r = VpnGate.examStored(app.store, s.key, prefer)
                                        if (r != null) {
                                            if (r.loss >= 100) VpnGate.bumpFail(app.store, s.key, r.via.ifEmpty { "لا استجابة" })
                                            else VpnGate.markOk(app.store, s.key, r)
                                            toast("${s.host}: ${r.lat}ms • فقد ${r.loss}%")
                                        } else {
                                            VpnGate.bumpFail(app.store, s.key, "لا استجابة")
                                            toast("${s.host}: لا استجابة — جرّب سيرفراً آخر")
                                        }
                                        busy = ""
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("فحص", maxLines = 1) }
                            Button(
                                onClick = { a.startOvpn(s) },
                                modifier = Modifier.weight(1f)
                            ) { Text("اتصال", maxLines = 1) }
                        }
                    }
                }
        }
    }
}
