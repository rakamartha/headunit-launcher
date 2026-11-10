package com.greycoat.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListenerService : NotificationListenerService() {

    companion object {
        var instance: MediaListenerService? = null
        var onMediaChangedCallback: ((title: String, artist: String, pkg: String, playing: Boolean) -> Unit)? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        pollActiveMediaSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        pollActiveMediaSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        pollActiveMediaSessions()
    }

    fun pollActiveMediaSessions() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            val comp = ComponentName(this, MediaListenerService::class.java)
            val controllers: List<MediaController> = msm.getActiveSessions(comp)
            if (controllers.isNotEmpty()) {
                val active = controllers.firstOrNull {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
                } ?: controllers.first()

                val meta = active.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: "Musik Headunit Aktif"
                val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: active.packageName
                val isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING
                onMediaChangedCallback?.invoke(title, artist, active.packageName, isPlaying)
            }
        } catch (_: Throwable) {}
    }
}
