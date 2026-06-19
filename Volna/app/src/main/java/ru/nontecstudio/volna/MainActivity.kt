package ru.nontecstudio.volna

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ru.nontecstudio.volna.service.WalkieTalkieService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var walkieService: WalkieTalkieService? = null
    private var isBound = false

    private lateinit var logAdapter: ArrayAdapter<String>
    private lateinit var statusTextView: TextView
    private lateinit var pttButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isSomeoneSpeaking = false

    // Генератор пиликанья (использует поток звонка/уведомлений для стабильности)
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    private val colorBackground = Color.parseColor("#121212")
    private val colorPrimary = Color.parseColor("#7F3DFF")
    private val colorTransmit = Color.parseColor("#FF3B30")
    private val colorBlocked = Color.parseColor("#2C2C2E")
    private val colorTextPrimary = Color.parseColor("#E5E5EA")
    private val colorTextSecondary = Color.parseColor("#8E8E93")

    private val resetStatusRunnable = Runnable {
        isSomeoneSpeaking = false
        statusTextView.text = "Эфир свободен"
        statusTextView.setTextColor(Color.parseColor("#34C759"))
        pttButton.isEnabled = true
        updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")

        // Звук освобождения эфира (Roger Beep) для локального пользователя
        playTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WalkieTalkieService.LocalBinder
            walkieService = binder.getService()
            isBound = true

            walkieService?.onFrameReceivedListener = { deviceName, _ ->
                val amIEncoding = (pttButton.text == "В ЭФИРЕ...")

                if (!amIEncoding) {
                    uiHandler.removeCallbacks(resetStatusRunnable)

                    if (!isSomeoneSpeaking) {
                        isSomeoneSpeaking = true
                        runOnUiThread {
                            // Звуковой сигнал о начале входящей передачи
                            playTone(ToneGenerator.TONE_SUP_RADIO_ACK, 100)

                            statusTextView.text = " $deviceName в эфире..."
                            statusTextView.setTextColor(Color.parseColor("#FF9500"))
                            pttButton.isEnabled = false
                            updateButtonUi(colorBlocked, "КАНАЛ ЗАНЯТ")

                            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            logAdapter.insert("[$time]  $deviceName", 0)
                        }
                    }
                    uiHandler.postDelayed(resetStatusRunnable, 1200)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            walkieService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBackground)
            setPadding(54, 54, 54, 54)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val statusBarHeight = insets.systemWindowInsetTop
                    val navigationBarHeight = insets.systemWindowInsetBottom
                    view.setPadding(54, 54 + statusBarHeight, 54, 54 + navigationBarHeight)
                    insets
                }
            }
        }

        statusTextView = TextView(this).apply {
            text = "Эфир свободен"
            textSize = 22f
            textColor = Color.parseColor("#34C759")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 16, 0, 32)
        }
        rootLayout.addView(statusTextView)

        val logTitle = TextView(this).apply {
            text = "ИСТОРИЯ ПЕРЕДАЧ"
            textSize = 12f
            textColor = colorTextSecondary
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.15f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(logTitle)

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            dividerHeight = 16
            isVerticalScrollBarEnabled = false
        }

        // Восстановление списка при повороте экрана
        val savedList = savedInstanceState?.getStringArrayList("ARG_LOG_HISTORY") ?: ArrayList<String>()

        logAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, savedList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.textColor = colorTextPrimary
                view.textSize = 15f
                view.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

                view.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C1C1E"))
                    cornerRadius = 20f
                }
                view.setPadding(32, 24, 32, 24)
                return view
            }
        }
        listView.adapter = logAdapter
        rootLayout.addView(listView)

        pttButton = Button(this).apply {
            textSize = 20f
            textColor = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 0.5f
            ).apply { setMargins(0, 48, 0, 16) }
        }

        updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")
        rootLayout.addView(pttButton)
        setContentView(rootLayout)

        checkPermissions()

        val intent = Intent(this, WalkieTalkieService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        pttButton.setOnTouchListener { _, event ->
            if (isSomeoneSpeaking) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Звук старта передачи перед открытием микрофона

                    walkieService?.audioEngine?.startRecording()
                    updateButtonUi(colorTransmit, "В ЭФИРЕ...")
                    statusTextView.text = " Вы в эфире..."
                    statusTextView.textColor = colorTransmit
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    walkieService?.audioEngine?.stopRecording()

                    // Локальный Roger Beep
                    playTone(ToneGenerator.TONE_PROP_BEEP, 150)

                    statusTextView.text = "Эфир свободен"
                    statusTextView.textColor = Color.parseColor("#34C759")
                    updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")
                    true
                }
                else -> false
            }
        }
    }

    // Сохранение состояния перед уничтожением Activity
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val listData = ArrayList<String>()
        for (i in 0 until logAdapter.count) {
            logAdapter.getItem(i)?.let { listData.add(it) }
        }
        outState.putStringArrayList("ARG_LOG_HISTORY", listData)
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        try {
            toneGenerator.startTone(toneType, durationMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateButtonUi(color: Int, textString: String) {
        pttButton.text = textString
        pttButton.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 75f
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 102)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        toneGenerator.release()
    }

    override fun onPause() {
        super.onPause()
        if (pttButton.text == "В ЭФИРЕ...") {
            walkieService?.audioEngine?.stopRecording()
            statusTextView.text = "Эфир свободен"
            statusTextView.textColor = Color.parseColor("#34C759")
            updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")
        }
    }

    private var TextView.textColor: Int
        get() = currentTextColor
        set(value) = setTextColor(value)
}