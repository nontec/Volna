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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
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
    private var isRadioActive = true
    private var isTransmitTimedOut = false // Флаг: была ли отсечка по таймауту в текущей сессии нажатия

    private lateinit var logAdapter: ArrayAdapter<String>
    private lateinit var statusTextView: TextView
    private lateinit var pttButton: Button
    private lateinit var powerButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isSomeoneSpeaking = false

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    private val MAX_TRANSMIT_TIME_MS = 30000L

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
        if (isRadioActive && !isTransmitTimedOut) {
            pttButton.isEnabled = true
            updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")
        }
        playTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    private val transmitTimeoutRunnable = Runnable {
        isTransmitTimedOut = true // Фиксируем таймаут
        stopBroadcasting()

        statusTextView.text = "Лимит передачи (30 сек) превышен"
        statusTextView.setTextColor(colorTransmit)

        // Кнопку НЕ выключаем через isEnabled = false, только меняем визуал!
        updateButtonUi(colorBlocked, "ОТПУСТИТЕ КНОПКУ")
        playTone(ToneGenerator.TONE_PROP_BEEP2, 300)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WalkieTalkieService.LocalBinder
            walkieService = binder.getService()
            isBound = true

            walkieService?.onFrameReceivedListener = { deviceName, _ ->
                if (isRadioActive && pttButton.text != "В ЭФИРЕ..." && !isTransmitTimedOut) {
                    uiHandler.removeCallbacks(resetStatusRunnable)
                    if (!isSomeoneSpeaking) {
                        isSomeoneSpeaking = true
                        runOnUiThread {
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
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val statusBarHeight = insets.systemWindowInsetTop
                    val navigationBarHeight = insets.systemWindowInsetBottom
                    view.setPadding(54, 54 + statusBarHeight, 54, 54 + navigationBarHeight)
                    insets
                }
            }
        }

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        statusTextView = TextView(this).apply {
            text = "Эфир свободен"
            textSize = 20f
            textColor = Color.parseColor("#34C759")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        powerButton = Button(this).apply {
            text = "ВЫКЛ. РАЦИЮ"
            textSize = 12f
            textColor = Color.WHITE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3A3C"))
                cornerRadius = 20f
            }
            setPadding(24, 0, 24, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            setOnClickListener {
                toggleRadioState()
            }
        }

        topPanel.addView(statusTextView)
        topPanel.addView(powerButton)
        rootLayout.addView(topPanel)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 32)
        }
        rootLayout.addView(spacer)

        val logTitle = TextView(this).apply {
            text = "ИСТОРИЯ ПЕРЕДАЧ"
            textSize = 12f
            textColor = colorTextSecondary
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }
        rootLayout.addView(logTitle)

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            dividerHeight = 16
            isVerticalScrollBarEnabled = false
        }

        val savedList = savedInstanceState?.getStringArrayList("ARG_LOG_HISTORY") ?: ArrayList<String>()
        logAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, savedList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.textColor = colorTextPrimary
                view.textSize = 15f
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.6f).apply {
                setMargins(0, 32, 0, 8)
            }
        }

        rootLayout.addView(pttButton)
        updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")

        // ПАНЕЛЬ С ССЫЛКАМИ НА ЮРИДИЧЕСКИЕ ДОКУМЕНТЫ
        val legalPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 0)
            }
        }

        val privacyLink = TextView(this).apply {
            text = "Политика конфиденциальности"
            textSize = 11f
            textColor = colorTextSecondary
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                openWebUrl("https://nontec.ru/volna/privacy.html")
            }
        }

        val dividerLink = TextView(this).apply {
            text = "•"
            textSize = 11f
            textColor = colorTextSecondary
            setPadding(4, 16, 4, 16)
        }

        val termsLink = TextView(this).apply {
            text = "Условия использования"
            textSize = 11f
            textColor = colorTextSecondary
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                openWebUrl("https://nontec.ru/volna/terms.html")
            }
        }

        legalPanel.addView(privacyLink)
        legalPanel.addView(dividerLink)
        legalPanel.addView(termsLink)
        rootLayout.addView(legalPanel)

        setContentView(rootLayout)

        // БЕЗОПАСНЫЙ ЗАПУСК СЕРВИСА: Проверяем наличие критического разрешения на микрофон
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRadioService()
        } else {
            checkPermissions()
        }

        pttButton.setOnTouchListener { _, event ->
            if (!isRadioActive || isSomeoneSpeaking) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isTransmitTimedOut) return@setOnTouchListener false

                    playTone(ToneGenerator.TONE_SUP_RADIO_ACK, 100)
                    walkieService?.audioEngine?.startRecording()
                    updateButtonUi(colorTransmit, "В ЭФИРЕ...")
                    statusTextView.text = "Вы в эфире..."
                    statusTextView.textColor = colorTransmit

                    uiHandler.postDelayed(transmitTimeoutRunnable, MAX_TRANSMIT_TIME_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    uiHandler.removeCallbacks(transmitTimeoutRunnable)

                    if (!isTransmitTimedOut) {
                        stopBroadcasting()
                    } else {
                        isTransmitTimedOut = false
                    }

                    statusTextView.text = "Эфир свободен"
                    statusTextView.textColor = Color.parseColor("#34C759")
                    updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")
                    true
                }
                else -> false
            }
        }
    }

    // Обработка ответа пользователя на системный запрос разрешений
    // Обработка ответа пользователя на системный запрос разрешений
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) {
            val micGranted = permissions.indices.firstOrNull { permissions[it] == Manifest.permission.RECORD_AUDIO }
                ?.let { grantResults[it] == PackageManager.PERMISSION_GRANTED } ?: false

            if (micGranted) {
                // Доступ получен, теперь создание FGS-сервиса с типом microphone разрешено системой
                startRadioService()
            } else {
                // Если юзер отказал, корректно переводим UI в заблокированное состояние без вылета
                statusTextView.text = "Требуется доступ к микрофону"
                statusTextView.setTextColor(colorTransmit)
                pttButton.isEnabled = false
                updateButtonUi(colorBlocked, "НЕТ ДОСТУПА К МИКРОФОНУ")
            }
        }
    }

    private fun openWebUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleRadioState() {
        if (isRadioActive) {
            isRadioActive = false
            powerButton.text = "ВКЛ. РАЦИЮ"
            powerButton.background = GradientDrawable().apply {
                setColor(colorPrimary)
                cornerRadius = 20f
            }
            statusTextView.text = "Рация отключена"
            statusTextView.textColor = colorTextSecondary
            pttButton.isEnabled = false
            updateButtonUi(colorBlocked, "РАЦИЯ ВЫКЛЮЧЕНА")
            stopRadioService()
        } else {
            // При повторном включении проверяем разрешения заново на случай, если их отозвали в настройках ОС
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                isRadioActive = true
                isTransmitTimedOut = false
                powerButton.text = "ВЫКЛ. РАЦИЮ"
                powerButton.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#3A3A3C"))
                    cornerRadius = 20f
                }
                statusTextView.text = "Эфир свободен"
                statusTextView.textColor = Color.parseColor("#34C759")
                pttButton.isEnabled = true
                updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")
                startRadioService()
            } else {
                checkPermissions()
            }
        }
    }

    private fun startRadioService() {
        val intent = Intent(this, WalkieTalkieService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopRadioService() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        val intent = Intent(this, WalkieTalkieService::class.java)
        stopService(intent)
    }

    private fun stopBroadcasting() {
        walkieService?.audioEngine?.stopRecording()
        playTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val listData = ArrayList<String>()
        for (i in 0 until logAdapter.count) {
            logAdapter.getItem(i)?.let { listData.add(it) }
        }
        outState.putStringArrayList("ARG_LOG_HISTORY", listData)
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        try { toneGenerator.startTone(toneType, durationMs) } catch (e: Exception) { e.printStackTrace() }
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
        stopRadioService()
        toneGenerator.release()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(transmitTimeoutRunnable)
        if (pttButton.text == "В ЭФИРЕ...") {
            stopBroadcasting()
            isTransmitTimedOut = false
            statusTextView.text = "Эфир свободен"
            statusTextView.textColor = Color.parseColor("#34C759")
            updateButtonUi(colorPrimary, "ЗАЖМИ И ГОВОРИ")
        }
    }

    private var TextView.textColor: Int
        get() = currentTextColor
        set(value) = setTextColor(value)
}
