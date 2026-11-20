package pl.lektor.pdf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.LocaleList
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.session.MediaButtonReceiver
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class PlayerState(
    val ttsReady: Boolean = false,
    val hasBook: Boolean = false,
    val bookId: String? = null,
    val title: String = "",
    val loading: Boolean = false,
    val loadProgress: Int = 0,
    val index: Int = 0,
    val total: Int = 0,
    /** Strona liczona od 1. */
    val page: Int = 0,
    val pageCount: Int = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    /** "auto" albo tag języka, np. "pl-PL". */
    val langSetting: String = ReaderService.LANG_AUTO,
    val effectiveLang: String = "",
    val voiceName: String? = null,
    /** Czas (elapsedRealtime) zatrzymania przez wyłącznik czasowy, 0 = wyłączony. */
    val sleepAt: Long = 0L
)

sealed interface ReaderEvent {
    data class Message(@StringRes val res: Int, val arg: String? = null) : ReaderEvent
    data class MissingVoice(val locale: Locale) : ReaderEvent
}

/**
 * Serwis odtwarzania: czyta tekst przez TextToSpeech jako usługa pierwszoplanowa typu
 * "mediaPlayback", dzięki czemu działa przy wyłączonym ekranie – jak odtwarzacz muzyki.
 */
class ReaderService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val LANG_AUTO = "auto"
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 4.0f
        private const val TAG = "ReaderService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1
        private const val PREFS = "reader"
    }

    inner class LocalBinder : Binder() {
        val service: ReaderService get() = this@ReaderService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequestCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // --- stan ---
    var book: Book? = null
        private set
    private var ttsReady = false
    private var playing = false
    private var index = 0
    private var speed = 1f
    private var langSetting = LANG_AUTO
    private var effectiveLocale: Locale? = null
    private var voiceName: String? = null
    private var loading = false
    private var loadProgress = 0
    private var loadJob: Job? = null
    private var pendingPlay = false
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var notificationShown = false
    private var lastNotificationKey = ""
    private var lastMetadataTitle: String? = null
    private var sleepAt = 0L
    private var errorStreak = 0

    /** Każde przerwanie czytania zwiększa numer – stare zdarzenia TTS są ignorowane. */
    private var generation = 0
    private var lastQueued = -1

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()

    // ------------------------------------------------------------------ cykl życia

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        speed = prefs.getFloat("speed", 1f).coerceIn(MIN_SPEED, MAX_SPEED)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
        setupSession()
        tts = TextToSpeech(this, this)
        restoreLastBook()
        publish()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // Uruchomiono nas przez startForegroundService – trzeba od razu wejść na pierwszy plan.
            startForegroundSafe()
            MediaButtonReceiver.handleIntent(session, intent)
            if (!playing) detachForeground()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playing) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        pause()
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { tts.shutdown() }
        session.isActive = false
        session.release()
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ TTS

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            emit(ReaderEvent.Message(R.string.err_tts_init))
            return
        }
        ttsReady = true
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(progressListener)
        applyLanguage()
        publish()
        if (pendingPlay) {
            pendingPlay = false
            play()
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            main.post { onSegmentStart(utteranceId) }
        }

        override fun onDone(utteranceId: String) {
            main.post { onSegmentDone(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            main.post { onSegmentError(utteranceId) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            main.post { onSegmentError(utteranceId) }
        }
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val p = id.split(':')
        if (p.size != 2) return null
        val g = p[0].toIntOrNull() ?: return null
        val i = p[1].toIntOrNull() ?: return null
        return g to i
    }

    private fun onSegmentStart(id: String) {
        val (g, i) = parseId(id) ?: return
        if (g != generation || !playing) return
        errorStreak = 0
        index = i
        // Zawsze trzymaj w kolejce jedno zdanie do przodu – bez przerw między zdaniami
        if (lastQueued < i + 1) enqueue(i + 1, TextToSpeech.QUEUE_ADD)
        saveProgress()
        publish()
    }

    private fun onSegmentDone(id: String) {
        val (g, i) = parseId(id) ?: return
        if (g != generation || !playing) return
        val total = book?.segments?.size ?: return
        if (i >= total - 1) {
            index = total
            pause()
            index = 0
            saveProgress()
            publish()
            emit(ReaderEvent.Message(R.string.finished))
        }
    }

    private fun onSegmentError(id: String) {
        val (g, i) = parseId(id) ?: return
        if (g != generation || !playing) return
        errorStreak++
        if (errorStreak > 3) {
            pause()
            emit(ReaderEvent.Message(R.string.err_tts_speak))
            return
        }
        val total = book?.segments?.size ?: return
        if (i + 1 < total) speakFrom(i + 1) else pause()
    }

    private fun speakFrom(i: Int) {
        val b = book ?: return
        generation++
        index = i.coerceIn(0, b.segments.size - 1)
        tts.setSpeechRate(speed)
        lastQueued = index - 1
        enqueue(index, TextToSpeech.QUEUE_FLUSH)
        enqueue(index + 1, TextToSpeech.QUEUE_ADD)
    }

    private fun enqueue(i: Int, mode: Int) {
        val b = book ?: return
        if (i >= b.segments.size) return
        tts.speak(b.segments[i].text, mode, Bundle(), "$generation:$i")
        lastQueued = i
    }

    private fun effectiveLocaleFor(setting: String): Locale =
        if (setting == LANG_AUTO) book?.detectedLocale ?: Locale.getDefault()
        else Locale.forLanguageTag(setting)

    private fun applyLanguage() {
        if (!ttsReady) return
        val locale = effectiveLocaleFor(langSetting)
        effectiveLocale = locale
        val r = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            emit(ReaderEvent.MissingVoice(locale))
        }
        val saved = prefs.getString("voice_${locale.language}", null)
        voiceName = null
        if (saved != null) {
            voicesFor(locale).firstOrNull { it.name == saved }?.let { v ->
                if (runCatching { tts.setVoice(v) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS) {
                    voiceName = v.name
                }
            }
        }
        tts.setSpeechRate(speed)
    }

    /** Języki obsługiwane przez zainstalowany silnik mowy. */
    fun availableLocales(): List<Locale> =
        if (!ttsReady) emptyList()
        else runCatching { tts.availableLanguages?.toList() }.getOrNull().orEmpty()

    /** Głosy dla danego języka (offline najpierw). */
    fun voicesFor(locale: Locale): List<Voice> {
        if (!ttsReady) return emptyList()
        val all = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
        return all
            .filter { it.locale.language == locale.language }
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.toLanguageTag() }, { it.name }))
    }

    // ------------------------------------------------------------------ sterowanie

    fun openBook(uri: Uri) {
        pause()
        loadJob?.cancel()
        loading = true
        loadProgress = 0
        publish()
        loadJob = scope.launch {
            try {
                val app = applicationContext
                val b = withContext(Dispatchers.IO) {
                    val title = BookRepository.displayName(app, uri)
                    BookRepository.load(app, uri, title) { p ->
                        ensureActive()
                        main.post { if (p != loadProgress) { loadProgress = p; publish() } }
                    }
                }
                prefs.edit().putString("last_id", b.id).putString("last_title", b.title).apply()
                setBook(b)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoTextException) {
                emit(ReaderEvent.Message(R.string.err_no_text))
            } catch (e: InvalidPasswordException) {
                emit(ReaderEvent.Message(R.string.err_password))
            } catch (e: Throwable) {
                Log.e(TAG, "openBook", e)
                emit(ReaderEvent.Message(R.string.err_open, e.message ?: e.javaClass.simpleName))
            } finally {
                loading = false
                publish()
            }
        }
    }

    private fun restoreLastBook() {
        val id = prefs.getString("last_id", null) ?: return
        val title = prefs.getString("last_title", "") ?: ""
        loading = true
        loadJob = scope.launch {
            try {
                val b = withContext(Dispatchers.IO) { BookRepository.loadCached(applicationContext, id, title) }
                if (b != null) setBook(b)
            } finally {
                loading = false
                publish()
            }
            if (pendingPlay && book != null && ttsReady) {
                pendingPlay = false
                play()
            }
        }
    }

    private fun setBook(b: Book) {
        book = b
        index = prefs.getInt("pos_${b.id}", 0).let { if (it >= b.segments.size) 0 else it.coerceAtLeast(0) }
        langSetting = prefs.getString("lang_${b.id}", LANG_AUTO) ?: LANG_AUTO
        applyLanguage()
        publish()
    }

    fun play() {
        val b = book
        if (b == null) {
            if (loadJob?.isActive == true) pendingPlay = true
            return
        }
        if (!ttsReady) {
            pendingPlay = true
            return
        }
        if (playing) return
        if (index >= b.segments.size) index = 0
        if (!requestFocus()) {
            emit(ReaderEvent.Message(R.string.err_focus))
            return
        }
        playing = true
        errorStreak = 0
        resumeOnFocusGain = false
        startForegroundSafe()
        registerNoisy()
        acquireWakeLock()
        speakFrom(index)
        publish()
    }

    fun pause() = pauseInternal(transient = false)

    private fun pauseInternal(transient: Boolean) {
        if (!playing) return
        playing = false
        generation++
        if (ttsReady) runCatching { tts.stop() }
        unregisterNoisy()
        releaseWakeLock()
        if (!transient) {
            abandonFocus()
            detachForeground()
        }
        saveProgress()
        publish()
    }

    fun togglePlay() = if (playing) pause() else play()

    fun seekTo(i: Int) {
        val b = book ?: return
        index = i.coerceIn(0, b.segments.size - 1)
        if (playing) speakFrom(index)
        saveProgress()
        publish()
    }

    fun skip(delta: Int) = seekTo(index + delta)

    fun goToPage(page1: Int) {
        val b = book ?: return
        seekTo(b.firstSegmentOfPage(page1 - 1))
    }

    fun setSpeed(value: Float) {
        val v = (Math.round(value * 20f) / 20f).coerceIn(MIN_SPEED, MAX_SPEED)
        if (v == speed) return
        speed = v
        prefs.edit().putFloat("speed", v).apply()
        if (ttsReady) tts.setSpeechRate(v)
        if (playing) speakFrom(index)   // nowe tempo od bieżącego zdania
        publish()
    }

    fun setLanguage(tag: String) {
        val b = book ?: return
        if (tag == langSetting) return
        langSetting = tag
        prefs.edit().putString("lang_${b.id}", tag).apply()
        applyLanguage()
        if (playing) speakFrom(index)
        publish()
    }

    fun setVoice(name: String?) {
        val locale = effectiveLocale ?: return
        prefs.edit().apply {
            if (name == null) remove("voice_${locale.language}") else putString("voice_${locale.language}", name)
        }.apply()
        applyLanguage()
        if (playing) speakFrom(index)
        publish()
    }

    private val sleepRunnable = Runnable {
        sleepAt = 0
        pause()
        emit(ReaderEvent.Message(R.string.sleep_done))
    }

    fun setSleepTimer(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        sleepAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        if (minutes > 0) main.postDelayed(sleepRunnable, minutes * 60_000L)
        publish()
    }

    private fun saveProgress() {
        val b = book ?: return
        prefs.edit().putInt("pos_${b.id}", index).apply()
    }

    // ------------------------------------------------------------------ słuchawki / fokus audio

    /** Odłączenie słuchawek (przewodowych lub Bluetooth) -> pauza. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playing) {
                pause()
                emit(ReaderEvent.Message(R.string.paused_unplugged))
            }
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (playing) {
                // np. połączenie telefoniczne, nawigacja – wznowimy później
                resumeOnFocusGain = true
                pauseInternal(transient = true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                play()
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
            .also { focusRequest = it }
        return AudioManagerCompat.requestAudioFocus(audioManager, req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
    }

    private fun acquireWakeLock() {
        val wl = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LektorPDF:reading")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        wl.acquire(12 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    // ------------------------------------------------------------------ sesja multimediów i powiadomienie

    private fun setupSession() {
        session = MediaSessionCompat(this, "LektorPDF").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() {
                    pause()
                    ServiceCompat.stopForeground(this@ReaderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    NotificationManagerCompat.from(this@ReaderService).cancel(NOTIF_ID)
                    notificationShown = false
                    stopSelf()
                }
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
                override fun onFastForward() = skip(10)
                override fun onRewind() = skip(-10)
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
            })
            setSessionActivity(contentIntent())
            isActive = true
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, localized().getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    /** Kontekst w języku wybranym w aplikacji (dla tekstów powiadomienia). */
    private fun localized(): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return this
        val cfg = Configuration(resources.configuration)
        cfg.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        return createConfigurationContext(cfg)
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val ctx = localized()
        fun action(icon: Int, title: Int, act: Long) = NotificationCompat.Action(
            icon, ctx.getString(title), MediaButtonReceiver.buildMediaButtonPendingIntent(this, act)
        )
        val playPause = if (playing) action(R.drawable.ic_pause, R.string.pause, PlaybackStateCompat.ACTION_PAUSE)
        else action(R.drawable.ic_play, R.string.play, PlaybackStateCompat.ACTION_PLAY)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title.ifEmpty { ctx.getString(R.string.app_name) })
            .setContentText(ctx.getString(R.string.notif_page, s.page, s.pageCount))
            .setSubText(formatSpeed(speed))
            .setContentIntent(contentIntent())
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(action(R.drawable.ic_rewind, R.string.rewind, PlaybackStateCompat.ACTION_REWIND))
            .addAction(action(R.drawable.ic_prev, R.string.previous, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(playPause)
            .addAction(action(R.drawable.ic_next, R.string.next, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .addAction(action(R.drawable.ic_forward, R.string.forward, PlaybackStateCompat.ACTION_FAST_FORWARD))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .build()
    }

    private fun startForegroundSafe() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, ReaderService::class.java))
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            notificationShown = true
            lastNotificationKey = ""
        } catch (e: Exception) {
            // np. ograniczenia startu z tła – czytanie i tak trwa, dopóki proces żyje
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun detachForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        lastNotificationKey = ""
        updateNotification()
    }

    private fun updateNotification() {
        if (!notificationShown || book == null) return
        val s = _state.value
        val key = "${s.title}|${s.page}|${s.pageCount}|$playing|$speed"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun updateSession() {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    index.toLong(), speed
                )
                .build()
        )
        val title = book?.title
        if (title != lastMetadataTitle) {
            lastMetadataTitle = title
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, localized().getString(R.string.app_name))
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (book?.segments?.size ?: 0).toLong())
                    .build()
            )
        }
    }

    // ------------------------------------------------------------------ publikacja stanu

    private fun publish() {
        val b = book
        val total = b?.segments?.size ?: 0
        val page = if (b != null && total > 0) b.segments[index.coerceIn(0, total - 1)].page + 1 else 0
        _state.value = PlayerState(
            ttsReady = ttsReady,
            hasBook = b != null,
            bookId = b?.id,
            title = b?.title.orEmpty(),
            loading = loading,
            loadProgress = loadProgress,
            index = index,
            total = total,
            page = page,
            pageCount = b?.pageCount ?: 0,
            playing = playing,
            speed = speed,
            langSetting = langSetting,
            effectiveLang = effectiveLocale?.toLanguageTag().orEmpty(),
            voiceName = voiceName,
            sleepAt = sleepAt
        )
        updateSession()
        updateNotification()
    }

    private fun emit(e: ReaderEvent) {
        _events.tryEmit(e)
    }
}

fun formatSpeed(speed: Float): String =
    if (Math.round(speed * 100f) % 10 == 0) String.format(Locale.US, "%.1f×", speed)
    else String.format(Locale.US, "%.2f×", speed)
