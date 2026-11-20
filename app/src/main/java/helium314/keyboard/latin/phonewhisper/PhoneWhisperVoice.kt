// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.phonewhisper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.voice.DictationEngine
import helium314.keyboard.latin.voice.ImeLog
import helium314.keyboard.latin.voice.VoiceButton
import helium314.keyboard.latin.voice.VoiceButtons
import helium314.keyboard.latin.voice.VoiceSettingsActivity

/**
 * Voice buttons in the suggestion strip, backed by the built-in dictation engine (from
 * Phone Whisper). Each button has its own colour, mode (tap to toggle / push-to-talk) and
 * model, configured in the voice settings (long-press any button). With a streaming model,
 * or an offline model on a push-to-talk button (pseudo-streaming by pauses), text is typed
 * live: the current piece as composing text, committed at each pause. Punctuation keys
 * pressed while speaking close the current piece and place the mark after it; any other
 * key ends dictation.
 */
class PhoneWhisperVoice(private val context: Context, private val host: Host) {

    /** What the voice buttons need from LatinIME. */
    interface Host {
        /** HeliBoard's cached input connection (keeps its text cache consistent). */
        val voiceConnection: RichInputConnection
        /** Commit any word being typed and reset HeliBoard's composing state. */
        fun voiceFinishTyping()
        /** Text changed under HeliBoard's feet: update shift state. */
        fun voiceTextChanged()
        /** Whether HeliBoard's dictionaries know [word]; null while no main dictionary is loaded. */
        fun voiceIsKnownWord(word: String): Boolean?
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private val main = Handler(Looper.getMainLooper())
    private var container: ViewGroup? = null
    private val views = LinkedHashMap<String, FrameLayout>()
    private var buttons: List<VoiceButton> = emptyList()

    private var state = State.IDLE
    /** Button that started the current utterance. */
    private var active: VoiceButton? = null

    private var liveActive = false
    private val liveText = StringBuilder()
    private var segBefore: CharSequence? = null
    private var segAfter: CharSequence? = null
    private val pendingPunct = mutableListOf<Char>()
    private var lastWasPunct = false
    private var cleanupId = 0

    private val conn get() = host.voiceConnection

    private val listener = object : DictationEngine.Listener {
        override fun onStateChanged(state: DictationEngine.State) {
            this@PhoneWhisperVoice.state = when (state) {
                DictationEngine.State.RECORDING -> State.RECORDING
                DictationEngine.State.TRANSCRIBING -> State.TRANSCRIBING
                DictationEngine.State.IDLE -> State.IDLE
            }
            if (state == DictationEngine.State.RECORDING) liveActive = DictationEngine.isLive
            render()
        }

        override fun onLevel(level: Float) {
            val v = active?.let { views[it.id] } ?: return
            val s = 1f + level * 0.15f
            v.scaleX = s
            v.scaleY = s
        }

        override fun onLivePartial(text: String) {
            if (!liveActive) return
            captureContext()
            conn.setComposingText(VoiceText.prepareDictation(text, segBefore!!, segAfter!!).insert, 1)
        }

        override fun onLiveSegment(text: String, forced: Boolean) {
            if (!liveActive) return
            captureContext()
            val d = VoiceText.prepareDictation(text, segBefore!!, segAfter!!)
            if (d.insert.isNotEmpty()) {
                conn.commitText(markUnknownWords(d.insert), 1)
                lastWasPunct = false
            } else {
                conn.finishComposingText()
            }
            liveText.append(d.insert)
            segBefore = null
            segAfter = null
            flushPunct()
            host.voiceTextChanged()
        }

        override fun onResult(text: String, notice: DictationEngine.Notice) {
            if (liveActive) {
                val typed = finishLive()
                if (DictationEngine.isCleanupEnabled() && typed.isNotBlank()) requestCleanup(typed)
            } else {
                insert(text)
            }
            noticeToast(notice)
            active = null
            render()
        }

        override fun onError(message: String) {
            if (liveActive) finishLive()
            active = null
            render()
            if (message.isNotBlank()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Wiring from LatinIME ---

    /** Put the buttons into the (re)created suggestion strip. */
    fun attach(container: ViewGroup?) {
        this.container = container
        rebuild()
    }

    fun onStartInputView() {
        DictationEngine.attach(context)
        ImeLog.init(context)
        rebuild() // settings may have changed
        // Warm the models of the visible buttons; they are released after idling.
        for (b in buttons) {
            val model = VoiceButtons.resolveModel(context, b) ?: continue
            DictationEngine.ensureLoaded(model)
        }
        DictationEngine.scheduleModelRelease()
    }

    fun onFinishInputView() {
        if (state != State.IDLE) DictationEngine.cancel(listener)
        if (liveActive) finishLive()
        state = State.IDLE
        active = null
        render()
    }

    fun onDestroy() {
        if (state != State.IDLE) DictationEngine.cancel(listener)
    }

    /**
     * Called for every key before HeliBoard handles it. Returns true if the key was consumed:
     * smart punctuation while speaking, or the first key that ends dictation.
     */
    fun interceptKey(codePoint: Int): Boolean {
        if (state == State.RECORDING) {
            if (liveActive && codePoint > 0 && codePoint.toChar() in VoiceText.SMART_MARKS) {
                pendingPunct.add(codePoint.toChar())
                if (!DictationEngine.cutSegment()) flushPunct()
                return true
            }
            stop()
            return true
        }
        if (state == State.TRANSCRIBING && liveActive) {
            // Keep what is typed so far and let the key through.
            conn.finishComposingText()
        }
        return false
    }

    // --- Buttons ---

    private fun rebuild() {
        val c = container ?: return
        c.removeAllViews()
        views.clear()
        // All configured buttons are shown; one without a usable model opens the settings when used.
        buttons = VoiceButtons.load(context)
        val size = context.resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) *
            VoiceButtons.sizePercent(context) / 100
        for (b in buttons) {
            val v = micButton(b)
            if (b.pushToTalk) v.pushToTalk(b) else v.setOnClickListener { it.haptic(); onTap(b) }
            c.addView(v, buttonParams(size))
            views[b.id] = v
        }
        render()
    }

    private fun onTap(b: VoiceButton) {
        when (state) {
            State.IDLE -> start(b)
            State.RECORDING -> stop()
            State.TRANSCRIBING -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.pushToTalk(b: VoiceButton) {
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.haptic()
                    if (state == State.IDLE) start(b)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (active?.id == b.id && state == State.RECORDING) stop()
                    true
                }
                else -> true
            }
        }
    }

    private fun start(b: VoiceButton) {
        if (!VoiceButtons.isReady(context, b)) {
            toast(R.string.phone_whisper_needs_model)
            openSettings()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(
                Intent(context, PhoneWhisperPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        host.voiceFinishTyping()
        liveActive = false
        liveText.setLength(0)
        segBefore = null
        segAfter = null
        pendingPunct.clear()
        lastWasPunct = false
        active = b
        val role = if (b.pushToTalk) DictationEngine.Role.PTT else DictationEngine.Role.TAP
        val explicit = b.model.takeIf { it.isNotBlank() }?.let { VoiceButtons.resolveModel(context, b) }
        when (DictationEngine.start(listener, cleanup = true, live = true, role = role, model = explicit)) {
            DictationEngine.StartResult.STARTED -> {}
            DictationEngine.StartResult.NO_PERMISSION -> { active = null; toast(R.string.phone_whisper_permissions_denied) }
            DictationEngine.StartResult.MIC_ERROR -> { active = null; toast(R.string.phone_whisper_mic_error) }
            DictationEngine.StartResult.BUSY -> { active = null }
        }
        render()
    }

    private fun stop() {
        if (state != State.RECORDING) return
        DictationEngine.stopAndTranscribe()
    }

    private fun openSettings() {
        context.startActivity(Intent(context, VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // --- Text ---

    private fun captureContext() {
        if (segBefore != null) return
        segBefore = conn.getTextBeforeCursor(64, 0) ?: ""
        segAfter = conn.getTextAfterCursor(16, 0) ?: ""
    }

    private fun insert(text: String) {
        val before = conn.getTextBeforeCursor(64, 0) ?: ""
        val after = conn.getTextAfterCursor(16, 0) ?: ""
        val d = VoiceText.prepareDictation(text, before, after)
        if (d.insert.isEmpty()) return
        host.voiceFinishTyping()
        conn.commitText(markUnknownWords(d.insert), 1)
        host.voiceTextChanged() // non-live results were already cleaned up by the engine
    }

    /**
     * Underline words HeliBoard's dictionaries do not know, the way a spell checker does
     * (SuggestionSpan with FLAG_MISSPELLED). Tapping such a word puts the cursor in it and
     * HeliBoard offers its dictionary suggestions in the strip. Recognizers mostly produce
     * real words, so this only catches non-words ("маскве"), not wrong real words.
     */
    private fun markUnknownWords(text: String): CharSequence {
        var spannable: SpannableString? = null
        for (m in WORD.findAll(text)) {
            val word = m.value
            if (word.length < 2 || word.any { it.isDigit() }) continue
            if (word.all { !it.isLetter() || it.isUpperCase() }) continue // acronyms
            val known = host.voiceIsKnownWord(word) ?: return text // no dictionary loaded
            if (known || host.voiceIsKnownWord(word.lowercase()) == true) continue
            val span = SuggestionSpan(context, arrayOf(), SuggestionSpan.FLAG_MISSPELLED)
            (spannable ?: SpannableString(text).also { spannable = it })
                .setSpan(span, m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable ?: text
    }

    private fun flushPunct() {
        val marks = pendingPunct.toList()
        pendingPunct.clear()
        for (mark in marks) {
            val before = conn.getTextBeforeCursor(32, 0) ?: ""
            val after = conn.getTextAfterCursor(1, 0) ?: ""
            val edit = VoiceText.punctuate(before, mark, after, append = lastWasPunct)
            if (edit.deleteBefore > 0) conn.deleteTextBeforeCursor(edit.deleteBefore)
            conn.commitText(edit.insert, 1)
            liveText.setLength(maxOf(0, liveText.length - edit.deleteBefore))
            liveText.append(edit.insert)
            lastWasPunct = true
        }
        if (marks.isNotEmpty()) host.voiceTextChanged()
    }

    /** End live typing; returns the dictated text (trimmed) for cleanup. */
    private fun finishLive(): String {
        conn.finishComposingText()
        flushPunct()
        liveActive = false
        segBefore = null
        segAfter = null
        host.voiceTextChanged()
        return liveText.toString().trim()
    }

    /** Clean up the live-typed phrase with the LLM and swap it in if the cursor is still after it. */
    private fun requestCleanup(target: String) {
        val id = ++cleanupId
        DictationEngine.cleanup(target) { cleaned, notice ->
            main.post {
                if (id != cleanupId) return@post
                noticeToast(notice)
                val text = cleaned.trim()
                if (text.isEmpty() || text == target || state != State.IDLE) return@post
                val before = conn.getTextBeforeCursor(target.length + 2, 0)?.toString() ?: return@post
                val trimmed = before.trimEnd(' ')
                if (!trimmed.endsWith(target)) return@post
                val trailing = before.length - trimmed.length
                host.voiceFinishTyping()
                conn.deleteTextBeforeCursor(target.length + trailing)
                conn.commitText(markUnknownWords(text + " ".repeat(trailing)), 1)
                host.voiceTextChanged()
            }
        }
    }

    private fun noticeToast(notice: DictationEngine.Notice) {
        when (notice) {
            DictationEngine.Notice.CLEANUP_NO_KEY -> toast(R.string.phone_whisper_cleanup_no_key)
            DictationEngine.Notice.CLEANUP_FAILED -> toast(R.string.phone_whisper_cleanup_failed)
            DictationEngine.Notice.NONE -> {}
        }
    }

    // --- Rendering ---

    private fun render() {
        for (b in buttons) {
            val v = views[b.id] ?: continue
            val isActive = active?.id == b.id
            val color = when {
                !isActive || state == State.IDLE -> b.color
                state == State.RECORDING -> REC_COLOR
                else -> BUSY_COLOR
            }
            (v.background as? GradientDrawable)?.setColor(color)
            if (!isActive || state != State.RECORDING) {
                v.scaleX = 1f
                v.scaleY = 1f
            }
        }
    }

    private fun micButton(b: VoiceButton): FrameLayout {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.sym_keyboard_voice_rounded)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        return FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(b.color) }
            // Icon padding scales with the button so small buttons keep a visible mic.
            val strip = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
            val pad = strip * VoiceButtons.sizePercent(context) / 100 / 6
            setPadding(pad, pad, pad, pad)
            addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            isClickable = true
            contentDescription = context.getString(if (b.pushToTalk) R.string.phone_whisper_ptt else R.string.phone_whisper_mic)
        }
    }

    private fun buttonParams(size: Int) = LinearLayout.LayoutParams(size, size).apply {
        gravity = Gravity.CENTER_VERTICAL
        val m = (context.resources.displayMetrics.density * 2.5f).toInt()
        setMargins(m, 0, m, 0)
    }

    private fun View.haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toast(res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

    companion object {
        private val WORD = Regex("[\\p{L}\\p{M}]+(?:['’-][\\p{L}\\p{M}]+)*")
        private const val REC_COLOR = 0xFFEF4444.toInt()
        private const val BUSY_COLOR = 0xFF8A9099.toInt()
    }
}
