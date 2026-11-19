// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.phonewhisper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.phonewhisper.RemoteProtocol as P

/**
 * Two small voice buttons in the suggestion strip, backed by the Phone Whisper app:
 * a blue tap-to-toggle mic and a teal push-to-talk mic (shown with a streaming model).
 * With a streaming model text is typed live as composing text and committed at each
 * pause; punctuation keys pressed while speaking close the current piece and place
 * the mark after it (smart punctuation), any other key ends dictation.
 */
class PhoneWhisperVoice(private val context: Context, private val host: Host) : PhoneWhisperClient.Events {

    /** What the voice buttons need from LatinIME. */
    interface Host {
        /** HeliBoard's cached input connection (keeps its text cache consistent). */
        val voiceConnection: RichInputConnection
        /** Commit any word being typed and reset HeliBoard's composing state. */
        fun voiceFinishTyping()
        /** Text changed under HeliBoard's feet: update shift state. */
        fun voiceTextChanged()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private val client = PhoneWhisperClient(context, this)
    private var tapButton: FrameLayout? = null
    private var pttButton: FrameLayout? = null

    private var state = State.IDLE
    private var pttActive = false
    private var streaming = false
    private var micButtons = "both"
    private var cleanupEnabled = false

    private var liveActive = false
    private val liveText = StringBuilder()
    private var segBefore: CharSequence? = null
    private var segAfter: CharSequence? = null
    private val pendingPunct = mutableListOf<Char>()
    private var lastWasPunct = false
    private var cleanupId = 0
    private var cleanupTarget: String? = null

    private val conn get() = host.voiceConnection

    // --- Wiring from LatinIME ---

    /** Put the buttons into the (re)created suggestion strip. */
    fun attach(container: ViewGroup?) {
        tapButton = null
        pttButton = null
        container ?: return
        container.removeAllViews()
        val size = (context.resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) * 0.78f).toInt()
        tapButton = micButton(TAP_COLOR, R.string.phone_whisper_mic).also { b ->
            b.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onTap() }
            container.addView(b, buttonParams(size))
        }
        pttButton = micButton(PTT_COLOR, R.string.phone_whisper_ptt).also { b ->
            b.pushToTalk()
            container.addView(b, buttonParams(size))
        }
        render()
    }

    fun onStartInputView() {
        client.connect()
        render()
    }

    fun onFinishInputView() {
        if (state != State.IDLE) client.cancel()
        if (liveActive) finishLive()
        state = State.IDLE
        pttActive = false
        render()
    }

    fun onDestroy() = client.disconnect()

    /**
     * Called for every key before HeliBoard handles it. Returns true if the key was consumed:
     * smart punctuation while speaking, or the first key that ends dictation.
     */
    fun interceptKey(codePoint: Int): Boolean {
        if (state == State.RECORDING) {
            if (liveActive && codePoint > 0 && codePoint.toChar() in VoiceText.SMART_MARKS) {
                pendingPunct.add(codePoint.toChar())
                client.cut()
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

    private fun onTap() {
        when (state) {
            State.IDLE -> start(ptt = false)
            State.RECORDING -> stop()
            State.TRANSCRIBING -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.pushToTalk() {
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (state == State.IDLE) start(ptt = true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pttActive && state == State.RECORDING) stop()
                    true
                }
                else -> true
            }
        }
    }

    private fun start(ptt: Boolean) {
        if (client.findService() == null) {
            toast(R.string.phone_whisper_not_installed)
            return
        }
        if (client.missingPermissions().isNotEmpty()) {
            context.startActivity(
                Intent(context, PhoneWhisperPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if (!client.isConnected) {
            client.connect()
            toast(R.string.phone_whisper_connecting)
            return
        }
        host.voiceFinishTyping()
        liveActive = false
        liveText.setLength(0)
        segBefore = null
        segAfter = null
        pendingPunct.clear()
        lastWasPunct = false
        if (!client.start(live = true, cleanup = true)) {
            toast(R.string.phone_whisper_mic_error)
            return
        }
        pttActive = ptt
        state = State.RECORDING
        render()
    }

    private fun stop() {
        if (state != State.RECORDING) return
        client.stop()
        state = State.TRANSCRIBING
        render()
    }

    // --- Engine events ---

    override fun onConnectionChanged(connected: Boolean) {
        if (!connected) {
            if (liveActive) finishLive()
            state = State.IDLE
            pttActive = false
        }
        render()
    }

    override fun onInfo(streaming: Boolean, micButtons: String, cleanup: Boolean) {
        this.streaming = streaming
        this.micButtons = micButtons
        this.cleanupEnabled = cleanup
        render()
    }

    override fun onStarted(live: Boolean) {
        liveActive = live
    }

    override fun onState(state: String) {
        this.state = when (state) {
            "RECORDING" -> State.RECORDING
            "TRANSCRIBING" -> State.TRANSCRIBING
            else -> State.IDLE
        }
        if (this.state == State.IDLE) pttActive = false
        render()
    }

    override fun onLevel(level: Float) {
        val b = (if (pttActive) pttButton else tapButton) ?: return
        val s = 1f + level * 0.15f
        b.scaleX = s
        b.scaleY = s
    }

    override fun onPartial(text: String) {
        if (!liveActive) return
        captureContext()
        conn.setComposingText(VoiceText.prepareDictation(text, segBefore!!, segAfter!!).insert, 1)
    }

    override fun onSegment(text: String, forced: Boolean) {
        if (!liveActive) return
        captureContext()
        val d = VoiceText.prepareDictation(text, segBefore!!, segAfter!!)
        if (d.insert.isNotEmpty()) {
            conn.commitText(d.insert, 1)
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

    override fun onResult(text: String, notice: String) {
        if (liveActive) {
            val typed = finishLive()
            if (cleanupEnabled && typed.isNotBlank()) requestCleanup(typed)
        } else {
            insert(text)
        }
        noticeToast(notice)
    }

    override fun onError(message: String, code: String?) {
        if (liveActive) finishLive()
        state = State.IDLE
        pttActive = false
        render()
        if (message.isNotBlank()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCleaned(id: Int, text: String, notice: String) {
        val target = cleanupTarget
        if (id != cleanupId || target == null) return
        cleanupTarget = null
        noticeToast(notice)
        val cleaned = text.trim()
        if (cleaned.isEmpty() || cleaned == target || state != State.IDLE) return
        // Only swap if the cursor is still right after the dictated text.
        val before = conn.getTextBeforeCursor(target.length + 2, 0)?.toString() ?: return
        val trimmed = before.trimEnd(' ')
        if (!trimmed.endsWith(target)) return
        val trailing = before.length - trimmed.length
        host.voiceFinishTyping()
        conn.deleteTextBeforeCursor(target.length + trailing)
        conn.commitText(cleaned + " ".repeat(trailing), 1)
        host.voiceTextChanged()
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
        conn.commitText(d.insert, 1)
        host.voiceTextChanged() // non-live results were already cleaned up by the engine
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

    private fun requestCleanup(typed: String) {
        cleanupTarget = typed
        client.cleanup(++cleanupId, typed)
    }

    private fun noticeToast(notice: String) {
        when (notice) {
            P.NOTICE_CLEANUP_NO_KEY -> toast(R.string.phone_whisper_cleanup_no_key)
            P.NOTICE_CLEANUP_FAILED -> toast(R.string.phone_whisper_cleanup_failed)
        }
    }

    // --- Rendering ---

    private fun render() {
        val tap = tapButton ?: return
        val ptt = pttButton ?: return
        val installed = client.findService() != null
        val showPtt = installed && streaming && (micButtons == "ptt" || micButtons == "both")
        val showTap = installed && (!showPtt || micButtons != "ptt")
        tap.visibility = if (showTap) View.VISIBLE else View.GONE
        ptt.visibility = if (showPtt) View.VISIBLE else View.GONE
        paint(tap, TAP_COLOR, active = !pttActive)
        paint(ptt, PTT_COLOR, active = pttActive)
    }

    private fun paint(button: FrameLayout, idle: Int, active: Boolean) {
        val color = when {
            !active || state == State.IDLE -> idle
            state == State.RECORDING -> REC_COLOR
            else -> BUSY_COLOR
        }
        (button.background as? GradientDrawable)?.setColor(color)
        if (!active || state != State.RECORDING) {
            button.scaleX = 1f
            button.scaleY = 1f
        }
    }

    private fun micButton(color: Int, desc: Int): FrameLayout {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.sym_keyboard_voice_rounded)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        return FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            val pad = (resources.displayMetrics.density * 6).toInt()
            setPadding(pad, pad, pad, pad)
            addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            isClickable = true
            contentDescription = context.getString(desc)
        }
    }

    private fun buttonParams(size: Int) = LinearLayout.LayoutParams(size, size).apply {
        gravity = Gravity.CENTER_VERTICAL
        val m = (context.resources.displayMetrics.density * 3).toInt()
        setMargins(m, 0, m, 0)
    }

    private fun toast(res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAP_COLOR = 0xFF2563EB.toInt()
        private const val PTT_COLOR = 0xFF0D9488.toInt()
        private const val REC_COLOR = 0xFFEF4444.toInt()
        private const val BUSY_COLOR = 0xFF8A9099.toInt()
    }
}
