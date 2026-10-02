package com.max.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.max.assistant.navigation.MaxApp
import com.max.assistant.ui.theme.MaxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MaxApplication).container
        setContent { MaxTheme { MaxApp(container) } }
    }
}
