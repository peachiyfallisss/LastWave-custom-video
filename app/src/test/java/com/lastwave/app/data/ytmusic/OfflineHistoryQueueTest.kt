package com.lastwave.app.data.ytmusic

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class OfflineHistoryQueueTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(file: File, scope: CoroutineScope) = YtMusicPreferences(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))

    private suspend fun listen(prefs: YtMusicPreferences, time: Long = 1L) = QueuedHistoryListen(
        accountScope = QueuedHistoryListen.scope(prefs.connection.first()), title = "Live song",
        artist = "Artist", album = "Live", videoId = "abcdefghijk", durationMs = 200_000, listenedAtMillis = time)

    @Test fun queueSurvivesRestartAndKeepsOriginalOrdering() = runBlocking {
        val file = File(temporary.root, "restart.preferences_pb")
        val job = SupervisorJob()
        val first = store(file, CoroutineScope(job + Dispatchers.IO))
        first.saveConnection(mapOf("session" to "test"), "Account", null, null, newSession = true)
        val late = listen(first, 20)
        val early = listen(first, 10)
        assertTrue(first.enqueueHistory(late))
        assertTrue(first.enqueueHistory(early))
        assertTrue(first.enqueueHistory(early))
        job.cancel(); job.join()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restarted = store(file, scope)
            assertEquals(listOf(early.id, late.id), restarted.pendingHistory.first().map { it.id })
            assertTrue(restarted.claimHistory(early))
            assertFalse(restarted.claimHistory(early))
            assertEquals(listOf(late.id), restarted.pendingHistory.first().map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun disableDisconnectAndChannelSwitchClearPendingListens() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = store(File(temporary.root, "gates.preferences_pb"), scope)
            prefs.saveConnection(mapOf("session" to "test"), "Account", null, null, newSession = true)
            val old = listen(prefs)
            prefs.enqueueHistory(old)
            prefs.setHistorySyncEnabled(false)
            assertTrue(prefs.pendingHistory.first().isEmpty())
            assertFalse(prefs.enqueueHistory(old))
            prefs.setHistorySyncEnabled(true)
            prefs.enqueueHistory(old)
            prefs.setSelectedChannel("other-channel", 0)
            assertTrue(prefs.pendingHistory.first().isEmpty())
            assertFalse(prefs.enqueueHistory(old))
            val current = listen(prefs)
            prefs.enqueueHistory(current)
            prefs.clearConnection()
            assertTrue(prefs.pendingHistory.first().isEmpty())
            assertFalse(prefs.claimHistory(current))
            prefs.saveConnection(mapOf("session" to "different"), "Other", null, null, newSession = true)
            assertFalse(prefs.enqueueHistory(current))
        } finally { scope.cancel() }
    }

    @Test fun credentialRefreshKeepsQueueButNewLoginCannotReplayIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = store(File(temporary.root, "refresh.preferences_pb"), scope)
            prefs.saveConnection(mapOf("session" to "old"), "Account", null, null, newSession = true)
            val pending = listen(prefs)
            prefs.enqueueHistory(pending)
            prefs.saveConnection(mapOf("session" to "refreshed"), "Account updated", null, null)
            val refreshed = prefs.pendingHistory.first().single()
            assertEquals(pending.id, refreshed.id)
            assertEquals(QueuedHistoryListen.scope(prefs.connection.first()), refreshed.accountScope)
            prefs.saveConnection(mapOf("session" to "other"), "Account updated", null, null, newSession = true)
            assertTrue(prefs.pendingHistory.first().isEmpty())
            assertFalse(prefs.enqueueHistory(refreshed))
        } finally { scope.cancel() }
    }
}
