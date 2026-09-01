package com.tinyggrok.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.tinyggrok.app.data.local.LocationRepository
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.share.IncomingShareRepository
import com.tinyggrok.app.data.share.toIncomingShare
import com.tinyggrok.app.ui.navigation.AppNavigation
import com.tinyggrok.app.ui.theme.AppTheme
import com.tinyggrok.app.ui.theme.TinyGrokTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var locationRepository: LocationRepository
    @Inject lateinit var incomingShareRepository: IncomingShareRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Rotation restores the same intent; skip so we don't re-apply a share
        // the user already edited or sent. Process death drops an unsent share.
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
        setContent {
            val theme by settingsRepository.theme.collectAsState(initial = AppTheme.DARK)
            TinyGrokTheme(appTheme = theme) {
                val navController = rememberNavController()
                AppNavigation(
                    navController = navController,
                    incomingShareRepository = incomingShareRepository
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val share = intent?.toIncomingShare(this) ?: return
        incomingShareRepository.offer(share)
    }

    override fun onStart() {
        super.onStart()
        // Optional one-shot GPS to fill a short-lived cache (not continuous tracking).
        //
        // Cold GNSS is slow; starting a single lookup when the UI appears means
        // "from my current location to X" can often use a cached fix at send time.
        // We do NOT keep GPS on in the background — that drains battery. The cache
        // has a TTL; after it expires the next send does one fresh lookup.
        // See LocationRepository (cache-with-timeout strategy).
        lifecycleScope.launch {
            val locationOn = runCatching {
                settingsRepository.locationEnabled.first()
            }.getOrDefault(true)
            if (locationOn) {
                locationRepository.startGpsWarmup()
            }
        }
    }

    override fun onStop() {
        // Cancel any in-flight warm-up session; the TTL cache is kept in memory.
        locationRepository.stopGpsWarmup()
        super.onStop()
    }
}
