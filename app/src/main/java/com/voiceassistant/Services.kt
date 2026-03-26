package com.voiceassistant

// ══════════════════════════════════════════════════════════════════════════════
//  Services.kt  —  WakeWordService + NotificationService
//                  + AssistantAccessibilityService + BootReceiver
// ══════════════════════════════════════════════════════════════════════════════

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

// ─── WakeWordService ──────────────────────────────────────────────────────────

class WakeWordService : Service() {

    companion object {
        var inst: WakeWordService? = null
        private const val TAG  = "WakeWord"
        private const val CH   = "VoiceAssistantCh"
        private const val NID  = 1
    }

    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var ttsReady = false
    private var processing = false
    private var wakeWord = "привет ассистент"

    override fun onCreate() {
        super.onCreate(); inst = this
        createChannel()
        startForeground(NID, note("Инициализация..."))
        initTTS()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        wakeWord = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getString(MainActivity.KEY_WAKE, "привет ассистент") ?: "привет ассистент"
        when (i?.action) {
            "TEST" -> speak("Голосовой ассистент работает. Скажи wake word.")
            "STOP" -> stopSelf()
        }
        return START_STICKY
    }

    // TTS ─────────────────────────────────────────────────────────────────────

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) { tts?.setLanguage(Locale.ENGLISH); ttsReady = true }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) {
                        if (u == "RESP") scope.launch { delay(400); processing = false; listenWake() }
                    }
                    @Deprecated("Deprecated")
                    override fun onError(u: String?) {}
                })
                scope.launch { delay(800); listenWake() }
            }
        }
    }

    fun speak(text: String, uid: String = "RESP") {
        if (!ttsReady) return
        killSR()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
    }

    // Wake word loop ───────────────────────────────────────────────────────────

    private fun listenWake() {
        if (processing) return
        notify("Жду: «$wakeWord»")
        listen(wake = true)
    }

    private fun listenCommand() {
        notify("🎙 Говори команду...")
        speak("Слушаю!", "ACK")
        scope.launch { delay(1600); listen(wake = false) }
    }

    private fun listen(wake: Boolean) {
        killSR()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}

            override fun onResults(r: Bundle?) {
                val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: ""
                Log.d(TAG, "Got: $text | wake=$wake")
                if (wake) {
                    if (isWakeWord(text)) listenCommand()
                    else scope.launch { delay(300); if (!processing) listenWake() }
                } else {
                    if (text.isNotEmpty()) processCmd(text)
                    else speak("Не расслышал, попробуй ещё раз")
                }
            }

            override fun onError(e: Int) {
                Log.d(TAG, "SR error $e")
                scope.launch { delay(1000); if (!processing) listenWake() }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (!wake) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            }
        }
        sr?.startListening(intent)
    }

    private fun isWakeWord(text: String): Boolean {
        val ww = wakeWord.lowercase()
        val variants = listOf(ww, "привет", "эй ассистент", "ассистент", "окей ассистент", "слушай")
        return variants.any { text.contains(it) }
    }

    // Command processing ──────────────────────────────────────────────────────

    private fun processCmd(cmd: String) {
        processing = true
        notify("⏳ «$cmd»")

        // Built-in fast commands
        val low = cmd.lowercase()
        when {
            low.contains("который час") || low.contains("сколько времени") -> {
                speak(SimpleDateFormat("HH:mm", Locale("ru")).format(Date()).let { "Сейчас $it" })
                return
            }
            low.contains("какой день") || low.contains("какое число") || low.contains("какая дата") -> {
                speak("Сегодня ${SimpleDateFormat("d MMMM, EEEE", Locale("ru")).format(Date())}")
                return
            }
            low.contains("стоп") || low.contains("выключись") || low.contains("остановись") -> {
                speak("Пока!")
                scope.launch { delay(2000); stopSelf() }
                return
            }
            low.contains("какие уведомления") || low.contains("новые уведомления") -> {
                val n = NotificationService.inst?.getRecent() ?: emptyList()
                speak(if (n.isEmpty()) "Новых уведомлений нет"
                else "Последние: ${n.take(3).joinToString(". ")}")
                return
            }
        }

        // Claude API
        scope.launch {
            val p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            val key = p.getString(MainActivity.KEY_API, "") ?: ""
            if (key.isEmpty()) { speak("API ключ не задан"); return@launch }

            val notifs = NotificationService.inst?.getRecent() ?: emptyList()
            val resp = ClaudeApi.ask(key, cmd, notifs)
            handleResp(resp, cmd)
        }
    }

    private fun handleResp(r: ClaudeApi.Resp, original: String) {
        Log.d(TAG, "Action=${r.action} param=${r.param}")
        when (r.action) {
            "OPEN_APP" -> {
                val opened = AppCtrl.open(this, r.param ?: "")
                speak(if (opened) r.speech else "Приложение не найдено")
            }
            "CALL" -> {
                speak(r.speech)
                scope.launch { delay(2000); PhoneCtrl.call(this@WakeWordService, r.param ?: ""); processing = false }
                return
            }
            "PLAY_MUSIC" -> { AppCtrl.music(this); speak(r.speech.ifEmpty { "Открываю музыку" }) }
            "SET_ALARM"  -> { AlarmCtrl.set(this, r.param ?: ""); speak(r.speech) }
            "SEARCH_WEB" -> { AppCtrl.web(this, r.param ?: original); speak(r.speech.ifEmpty { "Ищу..." }) }
            "SEND_MESSAGE" -> {
                val parts = r.param?.split("|") ?: emptyList()
                if (parts.size >= 1) AppCtrl.open(this, "telegram")
                speak(r.speech)
            }
            else -> speak(r.speech.ifEmpty { "Не понял команду" })
        }
    }

    // Notification ─────────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(CH, "Voice Assistant", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun note(text: String): Notification {
        val stopI = PendingIntent.getService(this, 0,
            Intent(this, WakeWordService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openI = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("🎙 Voice Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openI)
            .addAction(android.R.drawable.ic_delete, "Стоп", stopI)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NID, note(text))

    private fun killSR() {
        try { sr?.stopListening(); sr?.cancel(); sr?.destroy(); sr = null } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy(); inst = null
        scope.cancel(); killSR(); tts?.stop(); tts?.shutdown()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

// ─── NotificationService ──────────────────────────────────────────────────────

class NotificationService : NotificationListenerService() {

    companion object {
        var inst: NotificationService? = null
        private val recent = mutableListOf<String>()
    }

    override fun onCreate() { super.onCreate(); inst = this }
    override fun onDestroy() { super.onDestroy(); inst = null }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg.startsWith("android") || pkg == "com.android.systemui") return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getString("android.title") ?: ""
        val text  = ex.getString("android.text") ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                  catch (_: Exception) { pkg.split(".").last() }
        val msg = buildString {
            append(app)
            if (title.isNotEmpty()) append(": $title")
            if (text.isNotEmpty() && text != title) append(" — $text")
        }
        synchronized(recent) { recent.add(0, msg); if (recent.size > 20) recent.removeLast() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    fun getRecent(): List<String> = synchronized(recent) { recent.toList() }
}

// ─── AssistantAccessibilityService ───────────────────────────────────────────

class AssistantAccessibilityService : AccessibilityService() {

    companion object { var inst: AssistantAccessibilityService? = null }

    override fun onServiceConnected() { super.onServiceConnected(); inst = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); inst = null }

    fun back()   = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home()   = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents()= performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifs() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun click(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return find(root, text)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.contains(text, true) == true || node.contentDescription?.contains(text, true) == true)
            return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val f = find(c, text); if (f != null) return f
            c.recycle()
        }
        return null
    }

    fun swipeUp() {
        val dm = resources.displayMetrics
        val path = Path().apply { moveTo(dm.widthPixels/2f, dm.heightPixels*0.7f); lineTo(dm.widthPixels/2f, dm.heightPixels*0.3f) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
    }
}

// ─── BootReceiver ─────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(MainActivity.KEY_ON, false) && !p.getString(MainActivity.KEY_API, "").isNullOrEmpty()) {
            val svc = Intent(ctx, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
            else ctx.startService(svc)
        }
    }
}
