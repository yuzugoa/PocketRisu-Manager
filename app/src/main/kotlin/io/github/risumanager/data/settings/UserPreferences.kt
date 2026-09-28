package io.github.risumanager.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

const val DEFAULT_UPDATE_RECOVERY_RETENTION_DAYS = 7
const val MIN_UPDATE_RECOVERY_RETENTION_DAYS = 1
const val MAX_UPDATE_RECOVERY_RETENTION_DAYS = 365

internal fun sanitizeUpdateRecoveryRetentionDays(value: Int): Int =
    value.coerceIn(MIN_UPDATE_RECOVERY_RETENTION_DAYS, MAX_UPDATE_RECOVERY_RETENTION_DAYS)

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user-preferences")

class UserPreferences(private val context: Context) {
    val selectedTopLevelTab: Flow<String> = context.userPreferencesDataStore.data.map { preferences ->
        sanitizeTopLevelTab(preferences[SELECTED_TOP_LEVEL_TAB])
    }

    val themeMode: Flow<ThemeMode> = context.userPreferencesDataStore.data.map { preferences ->
        preferences[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    val updateRecoveryRetentionDays: Flow<Int> = context.userPreferencesDataStore.data.map { preferences ->
        sanitizeUpdateRecoveryRetentionDays(
            preferences[UPDATE_RECOVERY_RETENTION_DAYS] ?: DEFAULT_UPDATE_RECOVERY_RETENTION_DAYS,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.userPreferencesDataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setUpdateRecoveryRetentionDays(days: Int) {
        context.userPreferencesDataStore.edit {
            it[UPDATE_RECOVERY_RETENTION_DAYS] = sanitizeUpdateRecoveryRetentionDays(days)
        }
    }

    suspend fun setSelectedTopLevelTab(route: String) {
        context.userPreferencesDataStore.edit {
            it[SELECTED_TOP_LEVEL_TAB] = sanitizeTopLevelTab(route)
        }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme-mode")
        val UPDATE_RECOVERY_RETENTION_DAYS = intPreferencesKey("update-recovery-retention-days")
        val SELECTED_TOP_LEVEL_TAB = stringPreferencesKey("selected-top-level-tab")
    }
}

internal fun sanitizeTopLevelTab(route: String?): String = when (route) {
    "install", "settings" -> route
    else -> "home"
}
