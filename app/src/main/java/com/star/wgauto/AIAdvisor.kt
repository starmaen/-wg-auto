package com.star.wgauto

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * مستشار الذكاء 🧠: يقرأ قياسات التطبيق الفعلية (فحوصات WG/DNS/MTU/OpenVPN)
 * ويطلب من الذكاء اختيار الأفضل مع التعليل — بالعربية.
 * المزود والمفتاح يُحفظان في الجهاز فقط، ولا يُرسل شيء إلا سؤالك.
 */
@Composable
fun AIAdvisorDialog(app: WgApp, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(app.store.prefGet("aiProvider") ?: AIClient.PROVIDER_POLLINATIONS) }
    var apiKey by remember { mutableStateOf(app.store.prefGet("aiKey") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("🧠 مستشار الذكاء: اختر لي الأفضل") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "يقرأ فحوصاتك الحقيقية (كونفيجات + DNS + MTU + سيرفرات VPN) ويختار الأفضل مع السبب. " +
                        "افحص أولاً (فحص الكل / فحص السرعة) لتكون النصيحة مبنية على أرقام.",
                    fontSize = 12.sp
                )
                Picker(
                    "المزود",
                    if (provider == AIClient.PROVIDER_GEMINI) "Gemini (بمفتاحك)" else "مجاني بلا مفتاح",
                    listOf(
                        AIClient.PROVIDER_POLLINATIONS to "مجاني بلا مفتاح",
                        AIClient.PROVIDER_GEMINI to "Gemini (بمفتاحك)"
                    )
                ) { v ->
                    provider = v
                    app.store.prefPut("aiProvider", v)
                }
                if (provider == AIClient.PROVIDER_GEMINI) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim(); app.store.prefPut("aiKey", apiKey) },
                        label = { Text("مفتاح Gemini (من aistudio.google.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            answer = ""
                            isError = false
                            scope.launch {
                                val r = AIClient.ask(
                                    provider, apiKey,
                                    AIClient.systemPrompt(),
                                    AIClient.buildAdvisorPrompt(app)
                                )
                                answer = r.text
                                isError = !r.ok
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (busy) "يفكر…" else "اسأل الذكاء") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val r = AIClient.ask(
                                    provider, apiKey,
                                    "أنت مساعد عربي مختصر في تطبيق VPN.",
                                    "سجل OpenVPN الأخير:\n" +
                                        app.ovpn.logLines.value.takeLast(10).joinToString("\n")
                                            .ifEmpty { "(لا يوجد سجل بعد)" } +
                                        "\nاشرح باختصار: هل الاتصال سليم؟ وإن لا فما الخطوة التالية؟"
                                )
                                answer = r.text
                                isError = !r.ok
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("اشرح سجل VPN") }
                }
                Button(
                    onClick = {
                        if (busy) return@Button
                        val prompt = AIClient.buildApplyPrompt(app)
                        if (prompt == null) {
                            answer = "استورد كونفيجاً وDNS أولاً ثم افحص، وبعدها أطبّق تلقائياً."
                            isError = true
                            return@Button
                        }
                        busy = true
                        answer = ""
                        isError = false
                        scope.launch {
                            val r = AIClient.ask(
                                provider, apiKey,
                                "أنت محرك اختيار. ترد بسطر JSON واحد فقط بلا أي كلام آخر.",
                                prompt
                            )
                            if (!r.ok) {
                                answer = r.text
                                isError = true
                            } else {
                                val a = AIClient.applyResult(app, r.text)
                                answer = a.text
                                isError = !a.ok
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("⚡ اختر وطبّق تلقائياً (يتدخل في الإعدادات)") }
                if (busy) Text("⏳ يقرأ قياساتك ويسأل…", fontSize = 12.sp)
                if (answer.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                answer,
                                fontSize = 13.sp,
                                color = if (isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Text(
                    "مفتاحك لا يغادر جهازك إلا إلى مزود الذكاء الذي اخترته مع سؤالك فقط.",
                    fontSize = 11.sp, fontWeight = FontWeight.Light
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
        dismissButton = null
    )
}
