package com.jizizr.signaldock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jizizr.signaldock.ui.RecognitionHistoryDetailScreen
import com.jizizr.signaldock.ui.theme.SignaldockTheme

class RecognitionHistoryDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordId = intent.getStringExtra(EXTRA_RECORD_ID)
        if (recordId.isNullOrBlank()) {
            finish()
            return
        }

        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            SignaldockTheme {
                RecognitionHistoryDetailScreen(
                    recordId = recordId,
                    onBack = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
    }
}
