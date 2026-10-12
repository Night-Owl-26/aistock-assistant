package com.aistock.assistant

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aistock.assistant.data.LocaleHelper
import com.aistock.assistant.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var settings: com.aistock.assistant.data.SettingsStore

    override fun attachBaseContext(newBase: Context) {
        // Applies the language chosen in the app (English or Tamil) before any screen is created.
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The "Talk to AIStock" home-screen shortcut opens the assistant with the microphone already listening.
        val talk = (intent?.getStringExtra("talk") == "true" || intent?.getBooleanExtra("talk", false) == true) && savedInstanceState == null
        val chat = intent?.getBooleanExtra("chat", false) == true && savedInstanceState == null
        setContent { AppRoot(startTalking = talk, openChat = chat) }
    }

    override fun onStart() {
        super.onStart()
        // Hands-free listening can only be (re)started while the app is on screen.
        lifecycleScope.launch {
            try {
                if (settings.flow.first().handsFree && !com.aistock.assistant.voice.HandsFree.running.value) com.aistock.assistant.voice.HandsFree.start(this@MainActivity)
            } catch (e: Exception) { }
        }
    }
}
