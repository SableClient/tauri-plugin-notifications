package app.tauri.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedPushNotifierTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        UnifiedPushStateStore(context).showContent = true
        notificationManager = context.getSystemService(NotificationManager::class.java)

        // UnifiedPushNotifier builds its tap intent via
        // packageManager.getLaunchIntentForPackage(); register a fake
        // launcher activity so the lookup resolves in tests.
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        shadowOf(context.packageManager).addResolveInfoForIntent(
            launchIntent,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = context.packageName
                    name = "TestLauncherActivity"
                }
            }
        )
    }

    private fun shadowNotificationManager() = shadowOf(notificationManager)

    @Test
    fun successiveMessagesKeepHistoryAndDismissalClearsIt() {
        val room = "!history:example.org"
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$one", "first"))
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$two", "second"))
        fun texts(): List<String> = androidx.core.app.NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notificationManager.activeNotifications.single().notification)!!
            .messages.map { it.text.toString() }
        assertEquals(listOf("first", "second"), texts())
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$two", "second"))
        assertEquals(listOf("first", "second"), texts())
        notificationManager.cancelAll()
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$three", "third"))
        assertEquals(listOf("third"), texts())
    }

    @Test
    fun warmAndColdDeliveriesShareHistoryAndDoNotReAlertTheSameEvent() {
        val room = "!mixed:example.org"
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$first", "first"))
        val native = TauriNotificationManager(NotificationStorage(context, com.fasterxml.jackson.databind.ObjectMapper()),
            null, context, null)
        val request = app.tauri.notification.Notification().apply {
            id = UnifiedPushNotifier.roomNotificationId("@alice:example.org", room)
            title = "Room"
            channelId = "messages.v2"
            messages = listOf(NotificationMessage().apply {
                eventId = "\$second"
                body = "second"
                senderName = "Alice"
                timestamp = System.currentTimeMillis()
            })
        }
        native.schedule(request)
        val before = notificationManager.activeNotifications.single().notification.`when`
        native.schedule(request)
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$second", "second"))
        val posted = notificationManager.activeNotifications.single().notification
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)!!
        assertEquals(listOf("first", "second"), style.messages.map { it.text.toString() })
        assertEquals(before, posted.`when`)
        assertNull(posted.sound)
        assertNull(posted.vibrate)
    }

    @Test
    fun historyIsBoundedAndHiddenPreviewsDoNotLeakEarlierMessages() {
        val room = "!history:example.org"
        for (i in 1..12) UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$event$i", "body$i"))
        fun messages() = androidx.core.app.NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notificationManager.activeNotifications.single().notification)!!.messages
        assertEquals((5..12).map { "body$it" }, messages().map { it.text.toString() })
        UnifiedPushStateStore(context).showContent = false
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$event12", "body12"))
        assertTrue(messages().all { it.text.toString() == "New message" })
    }

    @Test
    fun dismissedEventDoesNotReturnFromEitherDeliveryPath() {
        val room = "!replay:example.org"
        val payload = pushPayload(room, "\$seen", "already seen")
        UnifiedPushNotifier.showFromPush(context, payload)
        notificationManager.cancelAll()
        UnifiedPushNotifier.showFromPush(context, payload)
        assertTrue(notificationManager.activeNotifications.isEmpty())
        val native = TauriNotificationManager(NotificationStorage(context, com.fasterxml.jackson.databind.ObjectMapper()), null, context, null)
        native.schedule(app.tauri.notification.Notification().apply {
            id = UnifiedPushNotifier.roomNotificationId("@alice:example.org", room)
            title = "Room"
            messages = listOf(NotificationMessage().apply {
                eventId = "\$seen"; body = "already seen"; timestamp = System.currentTimeMillis()
            })
        })
        assertTrue(notificationManager.activeNotifications.isEmpty())
    }

    @Test
    fun eventEvictedFromPreviewHistoryDoesNotReturnAsNew() {
        val room = "!replay:example.org"
        for (i in 1..12) UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$event$i", "body$i"))
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$event1", "body1"))
        val messages = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
            notificationManager.activeNotifications.single().notification)!!.messages
        assertEquals((5..12).map { "body$it" }, messages.map { it.text.toString() })
    }

    @Test
    fun decryptionFinishingOutOfOrderUpdatesItsOwnMessage() {
        val room = "!history:example.org"
        NotificationStorage(context, com.fasterxml.jackson.databind.ObjectMapper()).writeActionGroup(listOf(ActionType().apply {
            id = "sable-message"
            actions = listOf("sable-reply", "sable-mark-read").map { name -> NotificationAction().apply {
                id = name; title = name; input = name == "sable-reply"
            } }
        }))
        val state = UnifiedPushStateStore(context)
        state.pushUserId = "@alice:example.org"
        state.pushDeviceId = "DEVICE"
        state.showEncryptedContent = true
        mockkObject(PushPayloadDecryptor)
        try {
            every { PushPayloadDecryptor.decrypt(any(), any(), any(), any(), any()) } answers {
                UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$second", "second"))
                PushDecryptResult.Success("""{"content":{"body":"first"}}""")
            }
            val payload = JSONObject(pushPayload(room, "\$first"))
            payload.getJSONObject("notification").put("type", "m.room.encrypted")
            UnifiedPushNotifier.showFromPush(context, payload.toString())
            val messages = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                notificationManager.activeNotifications.single().notification)!!.messages
            assertEquals(listOf("first", "second"), messages.map { it.text.toString() })
            val intent = shadowOf(notificationManager.activeNotifications.single().notification.contentIntent).savedIntent
            val target = JSONObject(intent.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)!!).getJSONObject("extra")
            assertEquals("\$second", target.getString("event_id"))
            val actions = notificationManager.activeNotifications.single().notification.actions
            assertEquals(2, actions.size)
            for (action in actions) {
                val actionIntent = shadowOf(action.actionIntent).savedIntent
                val extra = JSONObject(actionIntent.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)!!).getJSONObject("extra")
                assertEquals("\$second", extra.getString("event_id"))
            }
        } finally { unmockkObject(PushPayloadDecryptor) }
    }

    @Test
    fun readClearIsAppliedBeforeNewMessagesAreQueued() {
        val user = "@alice:example.org"
        val room = "!room:example.org"
        val id = UnifiedPushNotifier.roomNotificationId(user, room)
        val previous = PushNotificationGate.revision(context, id)
        PushRenderWorker.enqueue(context, JSONObject().put("notification", JSONObject()
            .put("user_id", user).put("room_id", room)
            .put("counts", JSONObject().put("unread", 0))).toString())
        val next = PushNotificationGate.revision(context, id)
        assertNotEquals(previous, next)
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "\$new", userId = user), next)
        assertEquals(1, notificationManager.activeNotifications.size)
    }

    @Test
    fun dismissedQueuedPushCannotPostButNewPushCan() {
        val user = "@alice:example.org"
        val room = "!room:example.org"
        val id = UnifiedPushNotifier.roomNotificationId(user, room)
        val revision = PushNotificationGate.revision(context, id)
        val payload = pushPayload(room, "\$event", userId = user)
        PushNotificationGate.dismiss(context, id) { notificationManager.cancel(id) }
        UnifiedPushNotifier.showFromPush(context, payload, revision)
        assertTrue(notificationManager.activeNotifications.isEmpty())
        UnifiedPushNotifier.showFromPush(context, payload, PushNotificationGate.revision(context, id))
        assertEquals(1, notificationManager.activeNotifications.size)
    }

    @Test
    fun globalDismissalInvalidatesAllQueuedRoomsButRoomDismissalIsScoped() {
        val first = PushNotificationGate.revision(context, 1)
        val second = PushNotificationGate.revision(context, 2)
        PushNotificationGate.dismiss(context, 1) {}
        var posted = false
        PushNotificationGate.post(context, 2, second) { posted = true }
        assertTrue(posted)
        PushNotificationGate.dismiss(context, null) {}
        PushNotificationGate.post(context, 1, first) { fail("Dismissed room posted") }
        PushNotificationGate.post(context, 2, second) { fail("Cleared room posted") }
    }

    @Test
    fun recordsWhyARecipientBoundPushWasDropped() {
        UnifiedPushStateStore(context).pushUserId = "@alice:example.org"
        for ((recipient, outcome) in listOf(null to PushOutcome.MISSING_RECIPIENT, "@other:example.org" to PushOutcome.WRONG_RECIPIENT)) {
            UnifiedPushNotifier.showFromPush(context, pushPayload("!room:example.org", "\$event", userId = recipient))
            assertEquals(outcome.name, PushDiagnostics.drain(context).lastOutcome)
            assertTrue(notificationManager.activeNotifications.isEmpty())
        }
    }

    @Test
    fun aSignedInAccountOtherThanTheActiveOneStillNotifies() {
        val state = UnifiedPushStateStore(context)
        state.pushUserId = "@alice:example.org"
        state.pushDeviceId = "ALICE"
        state.pushAccounts = mapOf("@bob:example.org" to "BOB")

        UnifiedPushNotifier.showFromPush(
            context,
            pushPayload("!room:example.org", "\$bob", userId = "@bob:example.org")
        )
        assertEquals(1, notificationManager.activeNotifications.size)

        UnifiedPushNotifier.showFromPush(
            context,
            pushPayload("!other:example.org", "\$stranger", userId = "@carol:example.org")
        )
        assertEquals(PushOutcome.WRONG_RECIPIENT.name, PushDiagnostics.drain(context).lastOutcome)
        assertEquals(1, notificationManager.activeNotifications.size)
    }

    @Test
    fun hiddenPlaintextNeverReachesTheNotification() {
        context.getSharedPreferences("tauri-notifications", Context.MODE_PRIVATE)
            .edit().putBoolean("up-show-content", false).commit()
        UnifiedPushNotifier.showFromPush(context, pushPayload("!private:example.org", "$" + "private", "secret text"))
        val shown = notificationManager.activeNotifications.single().notification
        assertFalse(shown.extras.toString().contains("secret text"))
    }

    private fun pushPayload(
        roomId: String,
        eventId: String,
        body: String = "hello",
        userId: String? = "@alice:example.org"
    ): String {
        val notification = JSONObject()
            .put("room_id", roomId)
            .put("event_id", eventId)
            .put("room_name", "Room 1")
            .put("sender_display_name", "Alice")
            .put("type", "m.room.message")
            .put("content", JSONObject().put("body", body))
        return JSONObject()
            .put("notification", notification)
            .apply { if (userId != null) put("user_id", userId) }
            .toString()
    }

    private fun ringPayload(
        roomId: String,
        eventId: String,
        notificationType: String = "ring",
        userId: String? = "@alice:example.org"
    ): String {
        val notification = JSONObject()
            .put("room_id", roomId)
            .put("event_id", eventId)
            .put("room_name", "Room 1")
            .put("sender_display_name", "Alice")
            .put("type", "org.matrix.msc4075.rtc.notification")
            .put(
                "content",
                JSONObject()
                    .put("notification_type", notificationType)
                    .put("lifetime", 30000)
            )
        return JSONObject()
            .put("notification", notification)
            .apply { if (userId != null) put("user_id", userId) }
            .toString()
    }

    private fun canonicalId(roomId: String, userId: String = "@alice:example.org") =
        UnifiedPushNotifier.roomNotificationId(userId, roomId)

    @Test
    fun showFromPush_ntfyPayloadUsesRegisteredAccountForIdentityAndTap() {
        val payload = JSONObject(pushPayload("!ntfy:example.org", "\$ntfy", userId = null))
        payload.getJSONObject("notification").put("devices", org.json.JSONArray().put(
            JSONObject().put("pushkey", "https://ntfy.sh/up123?up=1").put("data",
                JSONObject().put("default_payload", JSONObject().put("user_id", "@alice:example.org")))
        ))
        UnifiedPushNotifier.showFromPush(context, payload.toString())
        val posted = shadowNotificationManager().getNotification(null, canonicalId("!ntfy:example.org"))
        assertNotNull("ntfy delivery must use the same identity as warm enrichment", posted)
        val source = shadowOf(posted!!.contentIntent).savedIntent.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)!!
        assertTrue(source.contains("@alice:example.org"))
    }

    @Test
    fun showFromPush_routesSupportedGatewayPayloadsToTheSameAccountAndRoom() {
        for (wrapper in listOf("flat", "object", "string")) {
            for (recipient in listOf("notification", "envelope", "device", "default_payload")) {
                val notification = JSONObject()
                    .put("room_id", "!contract:example.org")
                    .put("event_id", "\$contract-$wrapper-$recipient")
                    .put("type", "m.room.message")
                    .put("content", JSONObject().put("body", "contract message"))
                val envelope = when (wrapper) {
                    "flat" -> notification
                    else -> JSONObject()
                }
                when (recipient) {
                    "notification" -> notification.put("user_id", "@alice:example.org")
                    "envelope" -> envelope.put("user_id", "@alice:example.org")
                    else -> {
                        val data = JSONObject()
                        if (recipient == "device") data.put("user_id", "@alice:example.org")
                        else data.put("default_payload", JSONObject().put("user_id", "@alice:example.org"))
                        notification.put("devices", org.json.JSONArray().put(JSONObject().put("data", data)))
                    }
                }
                if (wrapper == "object") envelope.put("notification", notification)
                if (wrapper == "string") envelope.put("notification", notification.toString())
                notificationManager.cancelAll()
                UnifiedPushNotifier.showFromPush(context, envelope.toString())
                val posted = shadowNotificationManager().getNotification(null, canonicalId("!contract:example.org"))
                assertNotNull("$wrapper / $recipient", posted)
                assertTrue(posted!!.extras.getString(Notification.EXTRA_TEXT)!!.contains("contract message"))
                val source = shadowOf(posted.contentIntent).savedIntent.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)!!
                assertTrue(source.contains("@alice:example.org"))
            }
        }
    }

    @Test
    fun showFromPush_acceptsFlatMinimalPush() {
        val payload = JSONObject().put("room_id", "!flat:example.org")
            .put("event_id", "\$flat").put("user_id", "@alice:example.org").toString()
        UnifiedPushNotifier.showFromPush(context, payload)
        assertNotNull(shadowNotificationManager().getNotification(null, canonicalId("!flat:example.org")))
    }

    @Test
    fun showFromPush_encryptedRoomKeepsConversationTitle() {
        assertConversationTitle("m.room.encrypted")
    }

    @Test
    fun showFromPush_unencryptedRoomKeepsConversationTitle() {
        assertConversationTitle("m.room.message")
    }

    private fun assertConversationTitle(eventType: String) {
        val payload = JSONObject(pushPayload("!named:example.org", "event"))
        payload.getJSONObject("notification").put("type", eventType)

        UnifiedPushNotifier.showFromPush(context, payload.toString())

        val posted = shadowNotificationManager().getNotification(null, canonicalId("!named:example.org"))
        assertEquals("Room 1", posted.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        assertTrue(posted.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)!!
        assertEquals("Alice", style.messages.last().person?.name)
        assertEquals(if (eventType == "m.room.encrypted") "Encrypted message" else "hello", style.messages.last().text)
        val source = shadowOf(posted.contentIntent).savedIntent.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)!!
        assertTrue(source.contains("@alice:example.org"))
        assertTrue(source.contains("!named:example.org"))
        assertTrue(source.contains("event"))
    }

    @Test
    fun showFromPush_postsBaselineBeforeNativeDecryption() {
        val state = UnifiedPushStateStore(context)
        state.pushUserId = "@alice:example.org"
        state.pushDeviceId = "DEVICE"
        state.showEncryptedContent = true
        var baselineWasVisible = false
        mockkObject(PushPayloadDecryptor)
        try {
            every { PushPayloadDecryptor.decrypt(any(), any(), any(), any(), any()) } answers {
                baselineWasVisible = shadowNotificationManager().getNotification(null, canonicalId("!enc:example.org")) != null
                PushDecryptResult.Success("""{"content":{"body":"decrypted"}}""")
            }
            val payload = JSONObject(pushPayload("!enc:example.org", "\$enc"))
            payload.getJSONObject("notification").put("type", "m.room.encrypted")
            UnifiedPushNotifier.showFromPush(context, payload.toString())
            assertTrue("a slow native decrypt must not delay notification delivery", baselineWasVisible)
            val posted = shadowNotificationManager().getNotification(null, canonicalId("!enc:example.org"))!!
            assertTrue(posted.extras.getString(Notification.EXTRA_TEXT)!!.contains("decrypted"))
            assertEquals("Room 1", posted.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
            // The repost must not alert again.
            assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        } finally {
            unmockkObject(PushPayloadDecryptor)
        }
    }

    @Test
    fun showFromPush_honorsPrivacyChangesDuringDecryption() {
        val state = UnifiedPushStateStore(context)
        state.pushUserId = "@alice:example.org"
        state.pushDeviceId = "DEVICE"
        state.showEncryptedContent = true
        mockkObject(PushPayloadDecryptor)
        try {
            every { PushPayloadDecryptor.decrypt(any(), any(), any(), any(), any()) } answers {
                state.showEncryptedContent = false
                PushDecryptResult.Success("""{"content":{"body":"private plaintext"}}""")
            }
            val payload = JSONObject(pushPayload("!enc:example.org", "\$enc"))
            payload.getJSONObject("notification").put("type", "m.room.encrypted")
            UnifiedPushNotifier.showFromPush(context, payload.toString())
            val posted = shadowNotificationManager().getNotification(null, canonicalId("!enc:example.org"))!!
            assertTrue(posted.extras.getString(Notification.EXTRA_TEXT)!!.contains("Encrypted message"))
        } finally {
            unmockkObject(PushPayloadDecryptor)
        }
    }

    @Test
    fun showFromPush_acceptsV2DeviceAccountMetadata() {
        val payload = JSONObject(pushPayload("!v2:example.org", "\$v2", userId = null))
        payload.getJSONObject("notification").put("devices", org.json.JSONArray().put(
            JSONObject().put("data", JSONObject().put("user_id", "@alice:example.org"))
        ))
        UnifiedPushNotifier.showFromPush(context, payload.toString())
        assertNotNull(shadowNotificationManager().getNotification(null, canonicalId("!v2:example.org")))
    }

    @Test
    fun showFromPush_rejectsConflictingRecipientsAndControlMessages() {
        val payload = JSONObject(pushPayload("!conflict:example.org", "\$conflict"))
        payload.getJSONObject("notification").put("user_id", "@other:example.org")
        UnifiedPushNotifier.showFromPush(context, payload.toString())
        UnifiedPushNotifier.showFromPush(context, """{"notification":{"counts":{"unread":5}}}""")
        UnifiedPushNotifier.showFromPush(context, """{"app_id":"app","ack_token":"token"}""")
        assertTrue(shadowNotificationManager().allNotifications.isEmpty())
    }

    @Test
    fun showFromPush_clearsReadRoomsWithoutPostingAnAlert() {
        UnifiedPushNotifier.showFromPush(context, pushPayload("!read:example.org", "\$read"))
        UnifiedPushNotifier.showFromPush(context, """{"notification":{"user_id":"@alice:example.org","room_id":"!read:example.org","counts":{"unread":0}}}""")
        assertTrue(shadowNotificationManager().allNotifications.isEmpty())
    }

    @Test
    fun showFromPush_lateDecryptionDoesNotResurrectDismissedOrSupersededAlerts() {
        val state = UnifiedPushStateStore(context)
        state.pushUserId = "@alice:example.org"
        state.pushDeviceId = "DEVICE"
        state.showEncryptedContent = true
        mockkObject(PushPayloadDecryptor)
        try {
            for (supersede in listOf(false, true)) {
                every { PushPayloadDecryptor.decrypt(any(), any(), any(), any(), any()) } answers {
                    notificationManager.cancel(canonicalId("!enc:example.org"))
                    if (supersede) UnifiedPushNotifier.showFromPush(context,
                        pushPayload("!enc:example.org", "\$new", "newer message"))
                    PushDecryptResult.Success("""{"content":{"body":"stale plaintext"}}""")
                }
                val payload = JSONObject(pushPayload("!enc:example.org", "\$old-$supersede"))
                payload.getJSONObject("notification").put("type", "m.room.encrypted")
                UnifiedPushNotifier.showFromPush(context, payload.toString())
                val posted = shadowNotificationManager().getNotification(null, canonicalId("!enc:example.org"))
                if (supersede) assertTrue(posted!!.extras.getString(Notification.EXTRA_TEXT)!!.contains("newer message"))
                else assertNull(posted)
            }
        } finally { unmockkObject(PushPayloadDecryptor) }
    }

    @Test
    fun roomNotificationId_matchesDeployedJsAbsHashSemantics() {
        // Fixed vectors with expectations computed from Sable's JS
        // Math.abs(hashCode(userId + NUL + roomId)); they pin the key order,
        // the NUL separator, and the 32-bit wrap-around hash exactly.
        assertEquals(238601196, canonicalId("!r1:example.org")) // positive hash
        assertEquals(1475650254, canonicalId("!room-7:example.org")) // hash -1475650254
    }

    @Test
    fun roomNotificationId_mapsIntMinValueHashToZero() {
        // roomId = UTF-16 units 00D9 001B 000C 0009 001E: the key hashes to
        // exactly Int.MIN_VALUE under 32-bit wrap-around, where deployed JS
        // Math.abs would yield 2^31 — a value that cannot cross the Tauri
        // bridge as an Int, so the id must be mapped safely to 0.
        val roomId = String(charArrayOf(0x00D9.toChar(), 0x001B.toChar(), 0x000C.toChar(), 0x0009.toChar(), 0x001E.toChar()))
        assertEquals(Int.MIN_VALUE, "AAA${0.toChar()}$roomId".hashCode())
        assertEquals(0, UnifiedPushNotifier.roomNotificationId("AAA", roomId))
    }

    @Test
    fun fallbackNotificationId_fixedVectors() {
        assertEquals(461444550, UnifiedPushNotifier.fallbackNotificationId("!r1:example.org"))
        assertEquals(708055431, UnifiedPushNotifier.fallbackNotificationId("!room-2:example.org"))
        assertEquals(0, UnifiedPushNotifier.fallbackNotificationId(""))
    }

    @Test
    fun showFromPush_postsUntaggedNotificationWithCanonicalIdWithExpectedFlags() {
        UnifiedPushNotifier.showFromPush(context, pushPayload("!r1:example.org", "\$e1"))

        val id = canonicalId("!r1:example.org")
        val posted = shadowNotificationManager().getNotification(null, id)
        assertNotNull(posted)
        // A tagged lookup for the same id must find nothing: warm
        // enrichment/clear uses the untagged key (null, id).
        assertNull(shadowNotificationManager().getNotification("!r1:example.org", id))
        // A first cold push still alerts with the flag on: it only silences a repost.
        assertTrue(posted!!.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun showFromPush_sameRoomUpdatesInPlace_andTapCarriesLatestEvent() {
        UnifiedPushNotifier.showFromPush(context, pushPayload("!r1:example.org", "\$e1"))
        UnifiedPushNotifier.showFromPush(context, pushPayload("!r1:example.org", "\$e2", "second"))

        assertEquals(1, shadowNotificationManager().allNotifications.size)

        val posted = shadowNotificationManager().getNotification(null, canonicalId("!r1:example.org"))!!
        val savedIntent = shadowOf(posted.contentIntent).savedIntent
        val sourceJson = savedIntent.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)!!
        assertTrue(sourceJson.contains("\$e2"))
        assertFalse(sourceJson.contains("\$e1"))
        assertTrue(sourceJson.contains("!r1:example.org"))
    }

    @Test
    fun showFromPush_differentRoomsPostSeparatelyUntagged() {
        UnifiedPushNotifier.showFromPush(context, pushPayload("!r1:example.org", "\$e1"))
        UnifiedPushNotifier.showFromPush(context, pushPayload("!r2:example.org", "\$e2"))

        val shadow = shadowNotificationManager()
        assertNotNull(shadow.getNotification(null, canonicalId("!r1:example.org")))
        assertNotNull(shadow.getNotification(null, canonicalId("!r2:example.org")))
        assertEquals(2, shadow.allNotifications.size)
    }

    @Test
    fun showFromPush_withoutUserId_fallsBackToRoomKeyIdentity() {
        UnifiedPushNotifier.showFromPush(
            context,
            pushPayload("!r3:example.org", "\$e9", userId = null)
        )

        val shadow = shadowNotificationManager()
        assertNotNull(shadow.getNotification(null, UnifiedPushNotifier.fallbackNotificationId("!r3:example.org")))
        // The fallback deliberately does NOT match the warm-path identity.
        assertNull(shadow.getNotification(null, canonicalId("!r3:example.org")))
        assertEquals(1, shadow.allNotifications.size)
    }

    @Test
    fun ringIsRecognisedOnBothEventTypesAndOnlyForTheRingKind() {
        fun event(type: String, kind: String) = JSONObject()
            .put("type", type)
            .put("content", JSONObject().put("notification_type", kind))

        assertTrue(UnifiedPushNotifier.isRing(event("m.rtc.notification", "ring")))
        assertTrue(UnifiedPushNotifier.isRing(event("org.matrix.msc4075.rtc.notification", "ring")))
        // An announcement of a call already running is not a ring.
        assertFalse(UnifiedPushNotifier.isRing(event("m.rtc.notification", "notification")))
        assertFalse(UnifiedPushNotifier.isRing(event("m.room.message", "ring")))
        assertFalse(UnifiedPushNotifier.isRing(JSONObject().put("type", "m.rtc.notification")))
    }

    @Test
    fun callNotificationIdNeverCollidesWithTheRoomsConversation() {
        assertNotEquals(
            UnifiedPushNotifier.roomNotificationId("@alice:example.org", "!r1:example.org"),
            UnifiedPushNotifier.callNotificationId("@alice:example.org", "!r1:example.org")
        )
    }

    @Test
    fun showFromPush_callAnnouncementStaysAnOrdinaryMessage() {
        UnifiedPushNotifier.showFromPush(
            context,
            ringPayload("!r1:example.org", "\$ann", notificationType = "notification")
        )

        val callId = UnifiedPushNotifier.callNotificationId("@alice:example.org", "!r1:example.org")
        assertNull(shadowNotificationManager().getNotification(null, callId))
        assertNotNull(
            shadowNotificationManager().getNotification(null, canonicalId("!r1:example.org"))
        )
    }

    @Test
    fun showFromPush_postsMessagesOnHighImportanceMessagesChannel() {
        UnifiedPushNotifier.showFromPush(context, pushPayload("!r1:example.org", "\$e1"))

        val posted = shadowNotificationManager().getNotification(null, canonicalId("!r1:example.org"))!!
        assertEquals("messages.v2", posted.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            notificationManager.getNotificationChannel("messages.v2").importance
        )
        assertEquals(
            "android.app.Notification\$MessagingStyle",
            posted.extras.getString(Notification.EXTRA_TEMPLATE)
        )
    }

    @Test
    fun showFromPush_postsInvitesOnTheirOwnChannelWithoutReplyAction() {
        val notification = JSONObject()
            .put("room_id", "!r1:example.org")
            .put("event_id", "\$invite")
            .put("room_name", "Room 1")
            .put("sender_display_name", "Alice")
            .put("type", "m.room.member")
            .put("content", JSONObject().put("membership", "invite"))
        val payload = JSONObject()
            .put("notification", notification)
            .put("user_id", "@alice:example.org")
            .toString()

        UnifiedPushNotifier.showFromPush(context, payload)

        val posted = shadowNotificationManager().getNotification(null, canonicalId("!r1:example.org"))!!
        assertEquals("invites", posted.channelId)
        assertEquals("New Invitation", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Alice invites you to Room 1", posted.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(posted.actions == null || posted.actions.isEmpty())
    }

    @Test
    fun showFromPush_ignoresMalformedPayloads() {
        UnifiedPushNotifier.showFromPush(context, "not json at all")
        UnifiedPushNotifier.showFromPush(context, """{"foo": "bar"}""")

        assertTrue(shadowNotificationManager().allNotifications.isEmpty())
    }

    /** The setting is a privacy control, so the webview-less path must fail closed. */
    @Test
    fun showFromPush_encryptedRoom_keepsContentHiddenUnlessAllowed() {
        val encrypted = """{"notification":{"room_id":"!enc:example.org","event_id":"${'$'}e9",""" +
            """"type":"m.room.encrypted","sender":"@them:example.org",""" +
            """"content":{"algorithm":"m.megolm.v1.aes-sha2","ciphertext":"AAAA"},""" +
            """"user_id":"@me:example.org"}}"""

        UnifiedPushStateStore(context).showEncryptedContent = false
        UnifiedPushNotifier.showFromPush(context, encrypted)

        val posted = shadowNotificationManager().allNotifications.last()
        val text = posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("leaked content with the setting off: ${'$'}text", text.contains("Encrypted message"))
    }

    @Test
    fun showFromPush_encryptedRoom_defaultsToHidden() {
        assertTrue("must default to closed", !UnifiedPushStateStore(context).showEncryptedContent)
    }

    @Test
    fun showFromPush_onlyNotifiesOnceForARoomUntilTheAlertIsDismissed() {
        val room = "!once:example.org"
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "${'$'}one", "first"))
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "${'$'}two", "second"))
        val quiet = notificationManager.activeNotifications.single().notification
        assertTrue(quiet.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)

        UnifiedPushStateStore(context).notifyOnce = false
        UnifiedPushNotifier.showFromPush(context, pushPayload(room, "${'$'}three", "third"))
        val loud = notificationManager.activeNotifications.single().notification
        assertTrue(loud.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
    }
}
