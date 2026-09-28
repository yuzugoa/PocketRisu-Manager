package io.github.risumanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.risumanager.data.settings.ThemeMode
import io.github.risumanager.ui.AppViewModelFactory
import io.github.risumanager.ui.RisuManagerApp
import io.github.risumanager.ui.theme.RisuManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RisuManagerApplication).container
        val factory = AppViewModelFactory(this, container)
        setContent {
            val themeMode by container.preferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            RisuManagerTheme(themeMode) {
                RisuManagerApp(container = container, viewModelFactory = factory)
            }
        }
    }
}
