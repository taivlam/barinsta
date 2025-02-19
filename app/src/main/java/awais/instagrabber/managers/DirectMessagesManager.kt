package awais.instagrabber.managers

import android.content.ContentResolver
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import awais.instagrabber.models.enums.BroadcastItemType
import awais.instagrabber.models.Resource
import awais.instagrabber.models.Resource.Companion.error
import awais.instagrabber.models.Resource.Companion.loading
import awais.instagrabber.models.Resource.Companion.success
import awais.instagrabber.repositories.requests.directmessages.ThreadIdsOrUserIds
import awais.instagrabber.repositories.responses.User
import awais.instagrabber.repositories.responses.directmessages.DirectThread
import awais.instagrabber.repositories.responses.directmessages.RankedRecipient
import awais.instagrabber.utils.Constants
import awais.instagrabber.utils.Utils
import awais.instagrabber.utils.getCsrfTokenFromCookie
import awais.instagrabber.utils.getUserIdFromCookie
import awais.instagrabber.webservices.DirectMessagesService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

object DirectMessagesManager {
    val inboxManager: InboxManager by lazy { InboxManager(false) }
    val pendingInboxManager: InboxManager by lazy { InboxManager(true) }

    private val TAG = DirectMessagesManager::class.java.simpleName
    private val viewerId: Long
    private val deviceUuid: String
    private val csrfToken: String

    fun moveThreadFromPending(threadId: String) {
        val pendingThreads = pendingInboxManager.threads.value ?: return
        val index = pendingThreads.indexOfFirst { it.threadId == threadId }
        if (index < 0) return
        val thread = pendingThreads[index]
        val threadFirstDirectItem = thread.firstDirectItem ?: return
        val threads = inboxManager.threads.value
        var insertIndex = 0
        if (threads != null) {
            for (tempThread in threads) {
                val firstDirectItem = tempThread.firstDirectItem ?: continue
                val timestamp = firstDirectItem.getTimestamp()
                if (timestamp < threadFirstDirectItem.getTimestamp()) {
                    break
                }
                insertIndex++
            }
        }
        thread.pending = false
        inboxManager.addThread(thread, insertIndex)
        pendingInboxManager.removeThread(threadId)
        val currentTotal = inboxManager.getPendingRequestsTotal().value ?: return
        inboxManager.setPendingRequestsTotal(currentTotal - 1)
    }

    fun getThreadManager(
        threadId: String,
        pending: Boolean,
        currentUser: User,
        contentResolver: ContentResolver,
    ): ThreadManager {
        return ThreadManager(threadId, pending, currentUser, contentResolver, viewerId, csrfToken, deviceUuid)
    }

    suspend fun createThread(userPk: Long): DirectThread = DirectMessagesService.createThread(csrfToken, viewerId, deviceUuid, listOf(userPk), null)

    fun sendMedia(recipient: RankedRecipient, mediaId: String, itemType: BroadcastItemType, scope: CoroutineScope) {
        sendMedia(setOf(recipient), mediaId, itemType, scope)
    }

    fun sendMedia(
        recipients: Set<RankedRecipient>,
        mediaId: String,
        itemType: BroadcastItemType,
        scope: CoroutineScope,
    ) {
        val threadIds = recipients.mapNotNull{ it.thread?.threadId }
        val userIdsTemp = recipients.mapNotNull{ it.user?.pk }
        val userIds = userIdsTemp.map{ listOf(it.toString(10)) }
        sendMedia(threadIds, userIds, mediaId, itemType, scope) {
            inboxManager.refresh(scope)
        }
    }

    private fun sendMedia(
        threadIds: List<String>,
        userIds: List<List<String>>,
        mediaId: String,
        itemType: BroadcastItemType,
        scope: CoroutineScope,
        callback: (() -> Unit)?,
    ): LiveData<Resource<Any?>> {
        val data = MutableLiveData<Resource<Any?>>()
        data.postValue(loading(null))
        scope.launch(Dispatchers.IO) {
            try {
                if (itemType == BroadcastItemType.MEDIA_SHARE)
                    DirectMessagesService.broadcastMediaShare(
                        csrfToken,
                        viewerId,
                        deviceUuid,
                        UUID.randomUUID().toString(),
                        ThreadIdsOrUserIds(threadIds, userIds),
                        mediaId
                    )
                if (itemType == BroadcastItemType.PROFILE)
                    DirectMessagesService.broadcastProfile(
                        csrfToken,
                        viewerId,
                        deviceUuid,
                        UUID.randomUUID().toString(),
                        ThreadIdsOrUserIds(threadIds, userIds),
                        mediaId
                    )
                data.postValue(success(Any()))
                callback?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "sendMedia: ", e)
                data.postValue(error(e.message, null))
                callback?.invoke()
            }
        }
        return data
    }

    init {
        val cookie = Utils.settingsHelper.getString(Constants.COOKIE)
        viewerId = getUserIdFromCookie(cookie)
        deviceUuid = Utils.settingsHelper.getString(Constants.DEVICE_UUID)
        val csrfToken = getCsrfTokenFromCookie(cookie)
        require(!csrfToken.isNullOrBlank() && viewerId != 0L && deviceUuid.isNotBlank()) { "User is not logged in!" }
        this.csrfToken = csrfToken
    }
}