package com.lastwave.app.data.ytmusic

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable

/** No credentials or expiring tracking URLs are stored in the offline queue. */
@Serializable
data class QueuedHistoryListen(
    val id: String = UUID.randomUUID().toString(),
    val accountScope: String,
    val title: String,
    val artist: String,
    val album: String?,
    val videoId: String?,
    val durationMs: Long,
    val listenedAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        fun scope(account: YtConnection): String = scope(account.connectedAtMillis,
            account.onBehalfOfUser, account.authUserIndex, account.pageId)

        fun scope(epoch: Long, channel: String?, authIndex: Int?, pageId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(listOf(epoch.toString(), channel.orEmpty(), authIndex?.toString().orEmpty(), pageId)
                    .joinToString("\u0000").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
