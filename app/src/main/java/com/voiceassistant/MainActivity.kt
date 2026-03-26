package com.voiceassistant

// ══════════════════════════════════════════════════════════════════════════════
//  Main.kt  —  MainActivity + ClaudeApiService + AppController
//              + PhoneController + AlarmController
// ══════════════════════════════════════════════════════════════════════════════

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ─── MainActivity ─────────────────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "va_prefs"
        const val KEY_API  = "api_key"
        const val KEY_WAKE = "wake_word"
        const val KEY_ON   = "enabled"
        private const val REQ = 100
    }

    // UI refs
    private lateinit var etApi: EditText
    private lateinit var etWake: EditText
    private lateinit var tvApiStatus: TextView
    private lateinit var tvWakeStatus: TextView
    private lateinit var tvSvcStatus: TextView
    private lateinit var tvAudio: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvA11y: TextView
    private lateinit var tvNotif: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnA11y: Button
    private lateinit var btnNotif: Button

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        buildLayout()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun buildLayout() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF0D1117.toInt()) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 80)
        }

        fun tv(text: String, size: Float = 14f, color: Int = 0xFFE6EDF3.toInt()): TextView =
            TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }

        fun btn(label: String, bg: Int): Button = Button(this).apply {
            text = label; setBackgroundColor(bg); setTextColor(0xFFFFFFFF.toInt())
        }

        fun et(hint: String, isPass: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint; setHintTextColor(0xFF484F58.toInt())
            setTextColor(0xFFE6EDF3.toInt()); setBackgroundColor(0xFF21262D.toInt())
            setPadding(24, 20, 24, 20)
            if (isPass) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        fun lp(mb: Int = 16) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = mb }

        root.addView(tv("🎙 Voice Assistant", 26f, 0xFF58A6FF.toInt()).also {
            it.layoutParams = lp(8); it.setPadding(0, 20, 0, 0)
        })

        tvSvcStatus = tv("🔴 Остановлен", 13f, 0xFF8B949E.toInt()).also { root.addView(it, lp(28)) }

        // ── API Key block
        root.addView(tv("⚙️ Claude API Key", 16f).also { it.setTypeface(null, android.graphics.Typeface.BOLD); it.layoutParams = lp(10) })
        etApi = et("sk-ant-api03-...", isPass = true).also { it.setText(p.getString(KEY_API, "")); root.addView(it, lp(12)) }
        root.addView(tv("Wake word:", 12f, 0xFF8B949E.toInt()).also { it.layoutParams = lp(4) })
        etWake = et("привет ассистент").also { it.setText(p.getString(KEY_WAKE, "привет ассистент")); root.addView(it, lp(10)) }
        tvApiStatus = tv("❌ Ключ не задан", 13f, 0xFFF85149.toInt()).also { root.addView(it, lp(10)) }

        btn("💾 Сохранить", 0xFF238636.toInt()).also {
            it.layoutParams = lp(28); root.addView(it)
            it.setOnClickListener { saveSettings() }
        }

        // ── Permissions block
        root.addView(tv("🔐 Разрешения", 16f).also { it.setTypeface(null, android.graphics.Typeface.BOLD); it.layoutParams = lp(12) })
        tvAudio = tv("❌ Микрофон").also { root.addView(it, lp(6)) }
        tvPhone = tv("❌ Звонки").also { root.addView(it, lp(6)) }
        tvA11y  = tv("❌ Accessibility").also { root.addView(it, lp(6)) }
        tvNotif = tv("❌ Уведомления").also { root.addView(it, lp(10)) }

        btnA11y = btn("⚙️ Включить Accessibility", 0xFF1F6FEB.toInt()).also {
            it.layoutParams = lp(8); root.addView(it)
            it.setOnClickListener { showA11yDialog() }
        }
        btnNotif = btn("🔔 Разрешить уведомления", 0xFF1F6FEB.toInt()).also {
            it.layoutParams = lp(28); root.addView(it)
            it.setOnClickListener { showNotifDialog() }
        }

        // ── Controls block
        tvWakeStatus = tv("Wake word: «привет ассистент»", 13f, 0xFF8B949E.toInt()).also { root.addView(it, lp(12)) }
        btnToggle = btn("▶️ Запустить ассистента", 0xFF238636.toInt()).also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.height = 140
            it.textSize = 16f; it.layoutParams = lp(10); root.addView(it)
            it.setOnClickListener { toggleService() }
        }
        btn("🔊 Тест голоса", 0xFF30363D.toInt()).also {
            it.layoutParams = lp(28); root.addView(it)
            it.setOnClickListener {
                startService(Intent(this, WakeWordService::class.java).apply { action = "TEST" })
            }
        }

        // ── Hints
        root.addView(tv("💬 Примеры:", 15f).also { it.setTypeface(null, android.graphics.Typeface.BOLD); it.layoutParams = lp(10) })
        root.addView(tv("«открой ютуб»  •  «позвони маме»\n«который час»  •  «включи музыку»\n«будильник на 7 утра»  •  «найди погоду»\n«какие уведомления»  •  «стоп»",
            13f, 0xFF8B949E.toInt()).also { it.layoutParams = lp(0) })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun saveSettings() {
        val key  = etApi.text.toString().trim()
        val wake = etWake.text.toString().trim()
        if (key.isEmpty()) { Toast.makeText(this, "Введи API ключ!", Toast.LENGTH_SHORT).show(); return }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_API, key).putString(KEY_WAKE, wake.ifEmpty { "привет ассистент" }).apply()
        Toast.makeText(this, "✅ Сохранено", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun toggleService() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val on = p.getBoolean(KEY_ON, false)
        if (!on) {
            if (p.getString(KEY_API, "").isNullOrEmpty()) { Toast.makeText(this, "Нужен API ключ!", Toast.LENGTH_SHORT).show(); return }
            startForegroundServiceCompat(Intent(this, WakeWordService::class.java))
            p.edit().putBoolean(KEY_ON, true).apply()
            Toast.makeText(this, "🎙 Запущен! Скажи wake word.", Toast.LENGTH_LONG).show()
        } else {
            stopService(Intent(this, WakeWordService::class.java))
            p.edit().putBoolean(KEY_ON, false).apply()
        }
        updateUI()
    }

    private fun updateUI() {
        val p  = getSharedPreferences(PREFS, MODE_PRIVATE)
        val on = p.getBoolean(KEY_ON, false)
        val ww = p.getString(KEY_WAKE, "привет ассистент") ?: "привет ассистент"
        val hasKey = !p.getString(KEY_API, "").isNullOrEmpty()

        tvApiStatus.text = if (hasKey) "✅ API ключ задан" else "❌ Ключ не задан"
        tvApiStatus.setTextColor(if (hasKey) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
        tvWakeStatus.text = "Wake word: «$ww»"
        tvSvcStatus.text = if (on) "🟢 Активен — жду wake word" else "🔴 Остановлен"
        btnToggle.text = if (on) "⏹ Остановить ассистента" else "▶️ Запустить ассистента"
        btnToggle.setBackgroundColor(if (on) 0xFFB31D28.toInt() else 0xFF238636.toInt())

        val audio = hasAudio(); val phone = hasPhone()
        val a11y = isA11yOn(); val notif = isNotifOn()
        tvAudio.text = if (audio) "✅ Микрофон" else "❌ Микрофон"
        tvPhone.text = if (phone) "✅ Звонки" else "❌ Звонки"
        tvA11y.text  = if (a11y)  "✅ Accessibility" else "❌ Accessibility"
        tvNotif.text = if (notif) "✅ Уведомления" else "❌ Уведомления"
        btnA11y.visibility  = if (a11y)  View.GONE else View.VISIBLE
        btnNotif.visibility = if (notif) View.GONE else View.VISIBLE
    }

    private fun checkPermissions() {
        val need = mutableListOf<String>()
        if (!hasAudio()) need.add(Manifest.permission.RECORD_AUDIO)
        if (!hasPhone()) { need.add(Manifest.permission.CALL_PHONE); need.add(Manifest.permission.READ_CONTACTS) }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ)
    }

    private fun hasAudio() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasPhone() = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun isA11yOn(): Boolean {
        val svc = "${packageName}/${AssistantAccessibilityService::class.java.canonicalName}"
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(svc) == true
    }

    private fun isNotifOn() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun showA11yDialog() = AlertDialog.Builder(this)
        .setTitle("Accessibility Service")
        .setMessage("Открой → найди «Voice Assistant» → включи")
        .setPositiveButton("Открыть") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        .setNegativeButton("Отмена", null).show()

    private fun showNotifDialog() = AlertDialog.Builder(this)
        .setTitle("Доступ к уведомлениям")
        .setMessage("Разреши «Voice Assistant» читать уведомления")
        .setPositiveButton("Открыть") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        .setNegativeButton("Отмена", null).show()

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); updateUI()
    }
}

// ─── Claude API Service ───────────────────────────────────────────────────────

object ClaudeApi {

    private const val TAG = "ClaudeAPI"
    private const val URL = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-3-5-haiku-20241022"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Resp(val speech: String, val action: String = "NONE", val param: String? = null)

    private val SYSTEM = """
Ты голосовой ассистент Android. Отвечай коротко по-русски. Только JSON, без markdown.
Формат: {"speech":"текст вслух","action":"действие","param":"параметр или null"}
Действия: NONE, OPEN_APP(param=название), CALL(param=имя/номер), PLAY_MUSIC,
SEND_MESSAGE(param="контакт|текст"), SET_ALARM(param="HH:MM"), SEARCH_WEB(param=запрос)
Примеры:
"открой ютуб" → {"speech":"Открываю YouTube","action":"OPEN_APP","param":"youtube"}
"позвони маме" → {"speech":"Звоню маме","action":"CALL","param":"Мама"}
"поставь будильник на 7" → {"speech":"Ставлю на 07:00","action":"SET_ALARM","param":"07:00"}
""".trimIndent()

    suspend fun ask(apiKey: String, msg: String, notifs: List<String> = emptyList()): Resp =
        withContext(Dispatchers.IO) {
            val full = if (notifs.isEmpty()) msg else "$msg\n[Уведомления: ${notifs.take(5).joinToString(", ")}]"
            val body = JSONObject().apply {
                put("model", MODEL); put("max_tokens", 300); put("system", SYSTEM)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", full)))
            }
            val req = Request.Builder().url(URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            try {
                val res = http.newCall(req).execute()
                val txt = res.body?.string() ?: ""
                if (!res.isSuccessful) return@withContext Resp(
                    when (res.code) { 401 -> "Неверный API ключ"; 429 -> "Лимит запросов"; else -> "Ошибка ${res.code}" }
                )
                val content = JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
                parse(content)
            } catch (e: Exception) { Resp("Нет интернета: ${e.message}") }
        }

    private fun parse(raw: String): Resp {
        return try {
            val j = JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            Resp(j.optString("speech", "Готово"), j.optString("action", "NONE"),
                if (j.isNull("param")) null else j.optString("param"))
        } catch (e: Exception) { Resp(raw.take(200)) }
    }
}

// ─── App Controller ───────────────────────────────────────────────────────────

object AppCtrl {
    private val MAP = mapOf(
        "youtube" to "com.google.android.youtube",
        "ютуб" to "com.google.android.youtube",
        "telegram" to "org.telegram.messenger",
        "телеграм" to "org.telegram.messenger",
        "whatsapp" to "com.whatsapp", "ватсап" to "com.whatsapp",
        "instagram" to "com.instagram.android", "инстаграм" to "com.instagram.android",
        "vk" to "com.vkontakte.android", "вк" to "com.vkontakte.android", "вконтакте" to "com.vkontakte.android",
        "tiktok" to "com.zhiliaoapp.musically", "тикток" to "com.zhiliaoapp.musically",
        "spotify" to "com.spotify.music", "спотифай" to "com.spotify.music",
        "camera" to "com.android.camera2", "камера" to "com.android.camera2",
        "maps" to "com.google.android.apps.maps", "карты" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome", "хром" to "com.android.chrome",
        "settings" to "com.android.settings", "настройки" to "com.android.settings",
        "calculator" to "com.android.calculator2", "калькулятор" to "com.android.calculator2",
        "gmail" to "com.google.android.gm", "почта" to "com.google.android.gm",
        "contacts" to "com.android.contacts", "контакты" to "com.android.contacts",
        "netflix" to "com.netflix.mediaclient", "нетфликс" to "com.netflix.mediaclient",
        "zoom" to "us.zoom.videomeetings", "зум" to "us.zoom.videomeetings",
        "clock" to "com.android.deskclock", "часы" to "com.android.deskclock",
        "music" to "com.spotify.music", "музыка" to "com.spotify.music",
    )

    fun open(ctx: Context, name: String): Boolean {
        val low = name.lowercase()
        MAP.entries.filter { low.contains(it.key) || it.key.contains(low) }
            .forEach { if (launch(ctx, it.value)) return true }
        // scan installed
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .forEach { app ->
                val label = app.loadLabel(pm).toString().lowercase()
                if (label.contains(low) || low.contains(label)) {
                    pm.getLaunchIntentForPackage(app.activityInfo.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.also { ctx.startActivity(it); return true }
                }
            }
        return false
    }

    private fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
    }

    fun music(ctx: Context) {
        listOf("com.spotify.music","com.google.android.music","com.samsung.android.music","com.yandex.music")
            .firstOrNull { launch(ctx, it) }
    }

    fun web(ctx: Context, q: String) = try {
        ctx.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com/search?q=${Uri.encode(q)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ─── Phone Controller ─────────────────────────────────────────────────────────

object PhoneCtrl {
    fun call(ctx: Context, nameOrNum: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            WakeWordService.inst?.speak("Нет разрешения на звонки"); return
        }
        val isNum = nameOrNum.replace("+","").replace(" ","").replace("-","").all { it.isDigit() }
        val num = if (isNum) nameOrNum else findNum(ctx, nameOrNum) ?: run {
            WakeWordService.inst?.speak("Контакт «$nameOrNum» не найден"); return
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { WakeWordService.inst?.speak("Не удалось позвонить") }
    }

    private fun findNum(ctx: Context, name: String): String? {
        val low = name.lowercase()
        var cur: Cursor? = null
        return try {
            cur = ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)
            cur?.let {
                val ni = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val n = it.getString(ni)?.lowercase() ?: continue
                    if (n.contains(low) || low.contains(n)) return it.getString(pi)
                }
            }
            null
        } finally { cur?.close() }
    }
}

// ─── Alarm Controller ─────────────────────────────────────────────────────────

object AlarmCtrl {
    fun set(ctx: Context, time: String) {
        val p = time.trim().split(":")
        if (p.size != 2) return
        ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, p[0].toIntOrNull() ?: return)
            putExtra(AlarmClock.EXTRA_MINUTES, p[1].toIntOrNull() ?: return)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
