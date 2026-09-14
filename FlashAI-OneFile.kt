/*
 FLASH AI — Single Kotlin source file
 ------------------------------------
 This file contains the main app, command engine and voice service in ONE source file.

 IMPORTANT:
 Android still requires AndroidManifest.xml and Gradle configuration to build an APK.
 This file is therefore a convenient single-code-file version, not literally a complete
 one-file Android project.

 Features:
 - "Flash, YouTube kholo"
 - "Hey Flash, Instagram open karo"
 - "Flash, WhatsApp chalao"
 - Voice input + spoken reply
 - Finds launcher apps and opens them
*/

package com.flash.ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

object FlashCommandEngine {
    data class AppInfo(val name: String, val packageName: String)

    fun installedApps(context: Context): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    fun execute(context: Context, spoken: String): String {
        var command = spoken.lowercase(Locale.getDefault())
            .replace(Regex("[,.!?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        command = command.removePrefix("hey flash").removePrefix("flash").trim()

        if (command.isBlank()) return "Haan, bolo."

        val prefixes = listOf(
            "open ", "launch ", "start ", "khol ", "kholo ",
            "khol do ", "open karo ", "kholo na ", "chalao ", "chala "
        )
        for (prefix in prefixes) {
            if (command.startsWith(prefix)) {
                command = command.removePrefix(prefix).trim()
                break
            }
        }

        command = when {
            command == "yt" || command.contains("youtube") -> "youtube"
            command == "ig" || command == "insta" || command.contains("instagram") -> "instagram"
            command == "wa" || command.contains("whatsapp") -> "whatsapp"
            command.contains("chrome") -> "chrome"
            command.contains("camera") || command.contains("cam") -> "camera"
            else -> command
        }

        val wanted = normalize(command)
        val app = installedApps(context).firstOrNull {
            val n = normalize(it.name)
            n == wanted || (wanted.length >= 3 && n.contains(wanted))
        }

        if (app == null) return "\"$command\" naam ka installed app nahi mila."

        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return "${app.name} launch nahi ho saka."

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "${app.name} open kar diya."
    }

    private fun normalize(s: String) =
        s.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
}

class FlashAI : ComponentActivity() {
    private lateinit var tts: TextToSpeech

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startFlashService()
            else Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { tts.language = Locale("hi", "IN") }

        setContent {
            var command by remember { mutableStateOf("") }
            var result by remember { mutableStateOf("⚡ Flash ready") }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        Spacer(Modifier.height(30.dp))
                        Text("⚡ FLASH AI", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(12.dp))
                        Text(result)
                        Spacer(Modifier.height(20.dp))

                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Command") },
                            placeholder = { Text("Flash, YouTube kholo") }
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(onClick = {
                            result = FlashCommandEngine.execute(this@FlashAI, command)
                            tts.speak(result, TextToSpeech.QUEUE_FLUSH, null, "flash")
                        }) { Text("Run Command") }

                        Spacer(Modifier.height(10.dp))

                        Button(onClick = {
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                                startFlashService()
                                result = "Hey Flash mode started ⚡"
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }) { Text("🎙 Start Hey Flash") }

                        Spacer(Modifier.height(20.dp))
                        Text("Examples:")
                        Text("• Flash, YouTube kholo")
                        Text("• Hey Flash, Instagram open karo")
                        Text("• Flash, WhatsApp chalao")
                        Text("• Flash, Chrome kholo")
                    }
                }
            }
        }
    }

    private fun startFlashService() {
        val intent = Intent(this, FlashVoiceService::class.java)
        startForegroundService(intent)
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}

class FlashVoiceService : Service() {
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var listening = false

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            "flash_voice",
            "Flash Voice Assistant",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, "flash_voice")
            .setContentTitle("Flash AI")
            .setContentText("Listening for Hey Flash")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(77, notification)

        tts = TextToSpeech(this) { tts.language = Locale("hi", "IN") }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()

                if (text.lowercase().contains("hey flash") ||
                    text.lowercase().startsWith("flash")) {
                    val reply = FlashCommandEngine.execute(this@FlashVoiceService, text)
                    tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "flash_reply")
                }

                Handler(Looper.getMainLooper()).postDelayed({ listen() }, 600)
            }

            override fun onError(error: Int) {
                listening = false
                Handler(Looper.getMainLooper()).postDelayed({ listen() }, 800)
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        listen()
    }

    private fun listen() {
        if (listening) return
        listening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        recognizer.startListening(intent)
    }

    override fun onDestroy() {
        recognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
