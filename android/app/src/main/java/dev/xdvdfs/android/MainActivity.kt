package dev.xdvdfs.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotificationsOnce()
        setContent { XisoRoot() }
    }

    /** Progress and results are shown in notifications; the jobs work without them too. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (granted || prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val PREFS = "settings"
        const val KEY_DARK = "dark"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}

@Composable
fun XisoRoot() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val prefs = remember { context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE) }

    // Like the web app, follow the system setting until the switch is used.
    val system = isSystemInDarkTheme()
    var dark by remember {
        mutableStateOf(if (prefs.contains(MainActivity.KEY_DARK)) prefs.getBoolean(MainActivity.KEY_DARK, system) else system)
    }
    val bp = if (dark) DarkBp else LightBp

    // System bar icons follow the in-app theme.
    DisposableEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
    }

    // Do not let the screen turn off while a job runs.
    val active = JobRunner.activeTool != null
    DisposableEffect(active) {
        if (active) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val scheme = if (dark) {
        darkColorScheme(primary = bp.primary, background = bp.panelBg, surface = bp.panelBg)
    } else {
        lightColorScheme(primary = bp.primary, background = bp.panelBg, surface = bp.panelBg)
    }

    CompositionLocalProvider(LocalBp provides bp) {
        MaterialTheme(colorScheme = scheme) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(bp.outerBg),
                contentAlignment = Alignment.TopCenter,
            ) {
                // The page of the web app: a light/dark panel with green side borders.
                Box(
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .background(bp.panelBg)
                        .drawBehind {
                            val w = 2.dp.toPx()
                            drawRect(bp.accent, topLeft = Offset(0f, 0f), size = Size(w, size.height))
                            drawRect(bp.accent, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
                        },
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 15.dp, vertical = 15.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        Header(dark) {
                            dark = !dark
                            prefs.edit().putBoolean(MainActivity.KEY_DARK, dark).apply()
                        }
                        Intro()
                        Tools()
                        Footer()
                    }
                }
            }
        }
    }
}
