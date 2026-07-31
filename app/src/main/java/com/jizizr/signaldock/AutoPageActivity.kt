package com.jizizr.signaldock

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jizizr.signaldock.ui.AutoPageScreen
import com.jizizr.signaldock.ui.theme.SignaldockTheme

class AutoPageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            SignaldockTheme {
                AutoPageScreen(
                    onBack = ::finish,
                    onChanged = { setResult(Activity.RESULT_OK) },
                )
            }
        }
    }
}
