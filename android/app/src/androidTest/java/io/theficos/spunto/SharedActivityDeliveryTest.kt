package io.theficos.spunto

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.data.sync.AuthConfig
import io.theficos.spunto.data.sync.AuthStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SharedActivityDeliveryTest {
    @Test fun baselineIsSilentAndNewEventsPostOnePrivateWorkspaceAlert() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "alerts-" + UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        MockWebServer().use { server ->
            server.start()
            val config = AuthConfig(server.url("/").toString().trimEnd('/'), "synthetic", "test", "Phone", true, "account", "space", "editor")
            val auth = AuthStore(context)
            auth.save(config)
            val alerts = SharedActivityAlerts(context, config)
            val manager = app.getSystemService(NotificationManager::class.java)
            fun active() = manager.activeNotifications.singleOrNull { it.tag == config.replicaId }
            fun serve(vararg ids: Int) {
                val events = ids.joinToString(",") {
                    """{"kind":"note_created","id":"id-$it","workspace_id":"space","workspace_name":"Secret space","note_id":"note","created_at":$it,"read":false}"""
                }
                server.enqueue(MockResponse().setBody("""{"following":true,"followable":true,"notifications":[$events]}"""))
            }
            try {
                serve(2)
                alerts.configure(true)
                serve(2)
                alerts.poll()
                assertNull(active())
                serve(2, 1)
                alerts.poll()
                val notification = requireNotNull(active()).notification
                assertEquals(R.drawable.ic_shared_activity, notification.smallIcon.resId)
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Secret space"))
                manager.cancel(config.replicaId, active()!!.id)
                serve(2, 1)
                SharedActivityAlerts(context, config).poll()
                assertNull(active())
            } finally {
                alerts.configure(false)
                auth.clear()
            }
        }
    }
}
