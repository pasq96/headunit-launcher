package com.pasq96.headunit.media

import android.service.notification.NotificationListenerService

/** Empty on purpose: being an enabled listener is what authorises MediaSessionManager.getActiveSessions(). */
class MediaListener : NotificationListenerService()
