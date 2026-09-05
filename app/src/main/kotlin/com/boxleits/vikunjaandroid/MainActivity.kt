package com.boxleits.vikunjaandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.boxleits.vikunjaandroid.ui.VikunjaApp
import com.boxleits.vikunjaandroid.ui.theme.VikunjaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VikunjaTheme {
                VikunjaApp()
            }
        }
    }
}
