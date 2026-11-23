package com.yatesv.contacthub.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * A chat in a floating bubble (Android's chat bubbles / Samsung pop-ups). Opened from a message
 * notification's bubble; never offered when the app lock is on or the chat is hidden.
 */
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("conv").orEmpty()
        if (key.isBlank()) { finish(); return }
        setContent {
            HubTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChatScreen(key, onBack = { finish() })
                    MediaViewerOverlay()
                }
            }
        }
    }
}
