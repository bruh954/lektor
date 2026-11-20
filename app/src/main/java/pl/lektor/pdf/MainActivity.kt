package pl.lektor.pdf

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import pl.lektor.pdf.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val serviceFlow = MutableStateFlow<ReaderService?>(null)
    private val service get() = serviceFlow.value
    private var pendingUri: Uri? = null

    // stan widoku
    private var shownBookId: String? = null
    private var shownPage = -1
    private var pageStartIndex = 0
    private val ranges = ArrayList<IntRange>()
    private var highlighted = -1
    private var draggingProgress = false
    private var draggingSpeed = false
    private var langListKey = ""
    private var voiceListKey = ""
    private var langTags: List<String> = emptyList()
    private var voiceNames: List<String?> = emptyList()

    private val highlightSpan by lazy {
        BackgroundColorSpan(
            MaterialColors.getColor(b.txtPage, com.google.android.material.R.attr.colorPrimaryContainer)
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as ReaderService.LocalBinder).service
            serviceFlow.value = s
            pendingUri?.let { s.openBook(it); pendingUri = null }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceFlow.value = null
        }
    }

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            openUri(uri)
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        b.txtPage.movementMethod = LinkMovementMethod.getInstance()
        b.txtPage.highlightColor = android.graphics.Color.TRANSPARENT

        setupControls()
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                serviceFlow.filterNotNull().collectLatest { s ->
                    launch { s.events.collect { handleEvent(it) } }
                    s.state.collect { render(s, it) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ReaderService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        serviceFlow.value = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            intent?.action = null
            openUri(uri)
        }
    }

    private fun openUri(uri: Uri) {
        val s = service
        if (s != null) s.openBook(uri) else pendingUri = uri
    }

    // ------------------------------------------------------------------ kontrolki

    private fun setupControls() {
        b.btnOpenEmpty.setOnClickListener { openPdf.launch(arrayOf("application/pdf")) }
        b.btnPlay.setOnClickListener { service?.togglePlay() }
        b.btnPrev.setOnClickListener { service?.skip(-1) }
        b.btnNext.setOnClickListener { service?.skip(1) }
        b.btnRewind.setOnClickListener { service?.skip(-10) }
        b.btnForward.setOnClickListener { service?.skip(10) }

        b.sliderProgress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { draggingProgress = true }
            override fun onStopTrackingTouch(slider: Slider) {
                draggingProgress = false
                service?.seekTo(slider.value.toInt())
            }
        })
        b.sliderProgress.setLabelFormatter { v ->
            val bk = service?.book
            val i = v.toInt()
            if (bk != null && i in bk.segments.indices) getString(R.string.page_short, bk.segments[i].page + 1) else ""
        }

        // Tempo: suwak w dziesiątych częściach (5 = 0,5×, 40 = 4,0×)
        b.sliderSpeed.setLabelFormatter { v -> formatSpeed(v / 10f) }
        b.sliderSpeed.addOnChangeListener { _, v, fromUser -> if (fromUser) b.txtSpeed.text = formatSpeed(v / 10f) }
        b.sliderSpeed.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { draggingSpeed = true }
            override fun onStopTrackingTouch(slider: Slider) {
                draggingSpeed = false
                service?.setSpeed(slider.value / 10f)
            }
        })
        b.btnSlower.setOnClickListener { service?.let { it.setSpeed(it.state.value.speed - 0.1f) } }
        b.btnFaster.setOnClickListener { service?.let { it.setSpeed(it.state.value.speed + 0.1f) } }
        mapOf(b.btnS1 to 1f, b.btnS15 to 1.5f, b.btnS2 to 2f, b.btnS3 to 3f, b.btnS4 to 4f).forEach { (btn, sp) ->
            btn.setOnClickListener { service?.setSpeed(sp) }
        }

        b.spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val tag = langTags.getOrNull(pos) ?: return
                val s = service ?: return
                if (tag != s.state.value.langSetting) s.setLanguage(tag)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos !in voiceNames.indices) return
                val name = voiceNames[pos]
                val s = service ?: return
                if (name != s.state.value.voiceName) s.setVoice(name)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ------------------------------------------------------------------ renderowanie stanu

    private fun render(s: ReaderService, st: PlayerState) {
        b.loadingBar.isVisible = st.loading
        b.loadingBar.setProgressCompat(st.loadProgress, true)
        b.txtLoading.isVisible = st.loading
        b.txtLoading.text = getString(R.string.loading_pdf, st.loadProgress)
        b.emptyView.isVisible = !st.hasBook && !st.loading
        b.contentView.isVisible = st.hasBook

        if (!st.hasBook) return

        b.txtTitle.text = st.title
        val percent = if (st.total > 0) st.index * 100 / st.total else 0
        b.txtPageInfo.text = getString(R.string.page_info, st.page, st.pageCount, percent)

        b.btnPlay.setImageResource(if (st.playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnPlay.contentDescription = getString(if (st.playing) R.string.pause else R.string.play)

        // Postęp
        val maxV = (st.total - 1).coerceAtLeast(1).toFloat()
        if (b.sliderProgress.valueTo != maxV) {
            b.sliderProgress.value = 0f
            b.sliderProgress.valueTo = maxV
        }
        if (!draggingProgress) b.sliderProgress.value = st.index.coerceIn(0, maxV.toInt()).toFloat()

        // Tempo
        if (!draggingSpeed) {
            b.sliderSpeed.value = Math.round(st.speed * 10f).coerceIn(5, 40).toFloat()
            b.txtSpeed.text = formatSpeed(st.speed)
        }

        // Tekst strony z podświetleniem bieżącego zdania
        val book = s.book
        if (book != null && st.total > 0) {
            val pageIdx = st.page - 1
            if (st.bookId != shownBookId || pageIdx != shownPage) {
                buildPage(s, book, pageIdx)
                shownBookId = st.bookId
                shownPage = pageIdx
                highlighted = -1
            }
            if (st.index != highlighted) {
                highlight(st.index)
                highlighted = st.index
            }
        }

        renderLanguage(s, st)

        b.txtSleep.isVisible = st.sleepAt > 0
        if (st.sleepAt > 0) {
            val min = ((st.sleepAt - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1)
            b.txtSleep.text = getString(R.string.sleep_active, min.toInt())
        }
    }

    private fun buildPage(s: ReaderService, book: Book, pageIdx: Int) {
        val sb = SpannableStringBuilder()
        ranges.clear()
        val start = book.firstSegmentOfPage(pageIdx)
        pageStartIndex = start
        var i = start
        while (i < book.segments.size && book.segments[i].page == pageIdx) {
            val seg = book.segments[i]
            val st = sb.length
            sb.append(seg.text)
            val target = i
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { s.seekTo(target) }   // dotknij zdania = czytaj od niego
                override fun updateDrawState(ds: TextPaint) {}
            }, st, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ranges += st until sb.length
            sb.append(if (seg.paragraphEnd) "\n\n" else " ")
            i++
        }
        b.txtPage.setText(sb, TextView.BufferType.SPANNABLE)
        b.scroll.scrollTo(0, 0)
    }

    private fun highlight(index: Int) {
        val sp = b.txtPage.text as? Spannable ?: return
        sp.removeSpan(highlightSpan)
        val r = ranges.getOrNull(index - pageStartIndex) ?: return
        sp.setSpan(highlightSpan, r.first, r.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.txtPage.post {
            val layout = b.txtPage.layout ?: return@post
            val y = layout.getLineTop(layout.getLineForOffset(r.first))
            b.scroll.smoothScrollTo(0, (y - b.scroll.height / 3).coerceAtLeast(0))
        }
    }

    private fun renderLanguage(s: ReaderService, st: PlayerState) {
        if (!st.ttsReady) return
        val detected = s.book?.detectedLocale ?: Locale.getDefault()
        val key = "${st.bookId}|${st.langSetting}|${st.effectiveLang}|${AppCompatDelegate.getApplicationLocales().toLanguageTags()}"
        if (key != langListKey) {
            langListKey = key
            val preferred = listOf("pl-PL", "en-US", "en-GB", "ru-RU", "uk-UA", "de-DE")
            val available = s.availableLocales().map { it.toLanguageTag() }
            val tags = ArrayList<String>()
            tags += ReaderService.LANG_AUTO
            tags += preferred
            tags += available.filter { it !in preferred }.distinct()
                .sortedBy { Locale.forLanguageTag(it).getDisplayName(uiLocale()) }
            if (st.langSetting !in tags) tags += st.langSetting
            langTags = tags
            val labels = tags.map { t ->
                if (t == ReaderService.LANG_AUTO) getString(R.string.lang_auto, displayName(detected))
                else displayName(Locale.forLanguageTag(t))
            }
            b.spinnerLang.adapter = adapter(labels)
            b.spinnerLang.setSelection(tags.indexOf(st.langSetting).coerceAtLeast(0), false)
        }

        val vKey = "${st.effectiveLang}|${st.voiceName}"
        if (vKey != voiceListKey) {
            voiceListKey = vKey
            val voices = s.voicesFor(Locale.forLanguageTag(st.effectiveLang))
            voiceNames = listOf<String?>(null) + voices.map { it.name }
            val labels = listOf(getString(R.string.voice_default)) + voices.map { v ->
                val country = v.locale.getDisplayCountry(uiLocale())
                buildString {
                    append(v.name)
                    if (country.isNotEmpty()) append(" · ").append(country)
                    if (v.isNetworkConnectionRequired) append(" · ").append(getString(R.string.voice_online))
                }
            }
            b.spinnerVoice.adapter = adapter(labels)
            b.spinnerVoice.setSelection(voiceNames.indexOf(st.voiceName).coerceAtLeast(0), false)
        }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun uiLocale(): Locale = resources.configuration.locales[0]

    private fun displayName(l: Locale): String =
        l.getDisplayName(uiLocale()).replaceFirstChar { it.titlecase(uiLocale()) }

    // ------------------------------------------------------------------ zdarzenia

    private fun handleEvent(e: ReaderEvent) {
        when (e) {
            is ReaderEvent.Message -> {
                val text = if (e.arg != null) getString(e.res, e.arg) else getString(e.res)
                Snackbar.make(b.root, text, Snackbar.LENGTH_LONG).show()
            }
            is ReaderEvent.MissingVoice -> {
                Snackbar.make(b.root, getString(R.string.err_missing_lang, displayName(e.locale)), Snackbar.LENGTH_LONG)
                    .setAction(R.string.install_voice) {
                        runCatching {
                            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                        }.onFailure { openTtsSettings() }
                    }.show()
            }
        }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_open -> openPdf.launch(arrayOf("application/pdf"))
            R.id.action_goto -> showGoToPage()
            R.id.action_sleep -> showSleepTimer()
            R.id.action_app_lang -> showAppLanguage()
            R.id.action_tts_settings -> openTtsSettings()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showGoToPage() {
        val s = service ?: return
        val st = s.state.value
        if (!st.hasBook) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.page_number_hint, st.pageCount)
            setText(st.page.toString())
            selectAll()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let { s.goToPage(it.coerceIn(1, st.pageCount)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSleepTimer() {
        val s = service ?: return
        val minutes = intArrayOf(0, 5, 10, 15, 30, 45, 60, 90, 120)
        val labels = minutes.map { if (it == 0) getString(R.string.sleep_off) else getString(R.string.sleep_minutes, it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels.toTypedArray()) { _, which ->
                s.setSleepTimer(minutes[which])
                if (minutes[which] > 0) {
                    Snackbar.make(b.root, getString(R.string.sleep_set, minutes[which]), Snackbar.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showAppLanguage() {
        val tags = arrayOf("", "pl", "en", "ru")
        val labels = arrayOf(getString(R.string.app_lang_system), "Polski", "English", "Русский")
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val checked = tags.indexOfFirst { it.isNotEmpty() && current.startsWith(it) }.let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_language)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[which]))
            }
            .show()
    }

    private fun openTtsSettings() {
        runCatching {
            startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
