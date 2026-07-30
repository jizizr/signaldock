package com.jizizr.signaldock

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jizizr.signaldock.ui.DiagnosticLogScreen
import com.jizizr.signaldock.ui.theme.SignaldockTheme

class DiagnosticLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            SignaldockTheme {
                DiagnosticLogScreen(
                    onBack = ::finish,
                    onLoggingChanged = { setResult(Activity.RESULT_OK) },
                )
            }
        }
    }
}
