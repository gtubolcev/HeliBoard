// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.phonewhisper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.utils.Log
import kotlin.concurrent.thread
import kotlin.math.sqrt
import helium314.keyboard.latin.phonewhisper.RemoteProtocol as P

/**
 * Connection to the Phone Whisper app's dictation service. The keyboard records the
 * microphone (Phone Whisper runs in the background and may not) and streams PCM to the
 * service, which answers with live/final text. All [Events] arrive on the main thread.
 */
class PhoneWhisperClient(private val context: Context, private val events: Events) {

    interface Events {
        fun onConnectionChanged(connected: Boolean)
        fun onInfo(streaming: Boolean, micButtons: String, cleanup: Boolean)
        fun onStarted(live: Boolean)
        fun onState(state: String)
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onSegment(text: String, forced: Boolean)
        fun onResult(text: String, notice: String)
        fun onError(message: String, code: String?)
        fun onCleaned(id: Int, text: String, notice: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: Messenger? = null
    private var bound = false

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg -> dispatch(msg); true })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { Messenger(it) }
            send(P.MSG_INFO)
            send(P.MSG_PREPARE)
            events.onConnectionChanged(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            stopRecording()
            events.onConnectionChanged(false)
        }
    }

    @Volatile private var record: AudioRecord? = null

    val isConnected get() = service != null

    /** The Phone Whisper dictation service, if the app is installed. */
    fun findService(): ComponentName? =
        context.packageManager.queryIntentServices(Intent(P.ACTION_BIND), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }

    fun missingPermissions(): Array<String> =
        arrayOf(Manifest.permission.RECORD_AUDIO, P.PERMISSION).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    /** Bind if possible; when already connected just refresh info and warm up the model. */
    fun connect(): Boolean {
        if (service != null) {
            send(P.MSG_INFO)
            send(P.MSG_PREPARE)
            return true
        }
        if (bound) return true
        val component = findService() ?: return false
        if (missingPermissions().isNotEmpty()) return false
        bound = try {
            context.bindService(Intent(P.ACTION_BIND).setComponent(component), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "bind refused: ${e.message}")
            false
        }
        return bound
    }

    fun disconnect() {
        stopRecording()
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    /** Start an utterance and the microphone. False if not connected or the mic is unavailable. */
    fun start(live: Boolean, cleanup: Boolean): Boolean {
        if (service == null) return false
        send(P.MSG_START) { putBoolean(P.KEY_LIVE, live); putBoolean(P.KEY_CLEANUP, cleanup) }
        if (!startRecording()) {
            send(P.MSG_CANCEL)
            return false
        }
        return true
    }

    fun stop() {
        stopRecording()
        send(P.MSG_STOP)
    }

    fun cancel() {
        stopRecording()
        send(P.MSG_CANCEL)
    }

    fun cut() = send(P.MSG_CUT)

    fun cleanup(id: Int, text: String) = send(P.MSG_CLEANUP) { putInt(P.KEY_ID, id); putString(P.KEY_TEXT, text) }

    private fun send(what: Int, fill: Bundle.() -> Unit = {}): Boolean {
        val target = service ?: return false
        val msg = Message.obtain(null, what).apply {
            data = Bundle().apply(fill)
            replyTo = incoming
        }
        return try {
            target.send(msg)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun startRecording(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return false
        val minBuf = AudioRecord.getMinBufferSize(P.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = P.SAMPLE_RATE / 10 * 2 // 100 ms of 16-bit samples
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, P.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 2)
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord failed: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        record = rec
        thread(name = "PhoneWhisperMic") {
            val buf = ByteArray(chunk)
            while (record === rec) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val pcm = buf.copyOf(n)
                val level = level(pcm)
                main.post { events.onLevel(level) }
                send(P.MSG_AUDIO) { putByteArray(P.KEY_PCM, pcm) }
            }
            runCatching { rec.stop() }
            rec.release()
        }
        return true
    }

    private fun stopRecording() {
        record = null
    }

    private fun dispatch(msg: Message) {
        val d = msg.data ?: Bundle.EMPTY
        when (msg.what) {
            P.MSG_INFO_RESULT -> events.onInfo(
                d.getBoolean(P.KEY_STREAMING), d.getString(P.KEY_MIC_BUTTONS) ?: "both", d.getBoolean(P.KEY_CLEANUP)
            )
            P.MSG_STARTED -> events.onStarted(d.getBoolean(P.KEY_LIVE))
            P.MSG_STATE -> events.onState(d.getString(P.KEY_STATE).orEmpty())
            P.MSG_PARTIAL -> events.onPartial(d.getString(P.KEY_TEXT).orEmpty())
            P.MSG_SEGMENT -> events.onSegment(d.getString(P.KEY_TEXT).orEmpty(), d.getBoolean(P.KEY_FORCED))
            P.MSG_RESULT -> events.onResult(d.getString(P.KEY_TEXT).orEmpty(), d.getString(P.KEY_NOTICE) ?: P.NOTICE_NONE)
            P.MSG_ERROR -> {
                stopRecording()
                events.onError(d.getString(P.KEY_TEXT).orEmpty(), d.getString(P.KEY_ERROR_CODE))
            }
            P.MSG_CLEANED -> events.onCleaned(
                d.getInt(P.KEY_ID), d.getString(P.KEY_TEXT).orEmpty(), d.getString(P.KEY_NOTICE) ?: P.NOTICE_NONE
            )
        }
    }

    private fun level(pcm: ByteArray): Float {
        var sum = 0.0
        var i = 0
        var n = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            n++
            i += 2
        }
        if (n == 0) return 0f
        return (sqrt(sum / n) / 32768.0 * 8.5).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        private const val TAG = "PhoneWhisperClient"
    }
}
