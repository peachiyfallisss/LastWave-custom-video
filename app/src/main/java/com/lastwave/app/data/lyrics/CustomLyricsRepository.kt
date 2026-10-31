package com.lastwave.app.data.lyrics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Singleton
class CustomLyricsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("lastwave_custom_lyrics", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(key: String): CustomLyricsDocument? = withContext(Dispatchers.IO) {
        preferences.getString(key, null)?.let { json.decodeFromString<CustomLyricsDocument>(it) }
    }

    suspend fun save(key: String, document: CustomLyricsDocument) = withContext(Dispatchers.IO) {
        document.result() // Never replace a valid edit with an invalid document.
        check(preferences.edit().putString(key, json.encodeToString(document)).commit()) { "Could not save lyrics." }
    }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(key).commit()) { "Could not remove custom lyrics." }
    }
}
