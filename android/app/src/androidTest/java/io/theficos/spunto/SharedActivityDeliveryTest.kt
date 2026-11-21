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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
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
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val config = AuthConfig("http://127.0.0.1:${server.localPort}", "synthetic", "test", "Phone", true, "account", "space", "editor")
            val auth = AuthStore(context)
            auth.save(config)
            val alerts = SharedActivityAlerts(context, config)
            val manager = app.getSystemService(NotificationManager::class.java)
            fun active() = manager.activeNotifications.singleOrNull { it.tag == config.replicaId }
            suspend fun serve(vararg ids: Int, action: suspend () -> Unit) = coroutineScope {
                val events = ids.joinToString(",") {
                    """{"kind":"note_created","id":"id-$it","workspace_id":"space","workspace_name":"Secret space","note_id":"note","created_at":$it,"read":false}"""
                }
                val body = """{"following":true,"followable":true,"notifications":[$events]}""".toByteArray()
                launch(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        assertEquals("GET /api/v1/notifications HTTP/1.1", reader.readLine())
                        val headers = generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
                        assertTrue(headers.any { it.equals("Authorization: Bearer synthetic", ignoreCase = true) })
                        val stream = socket.getOutputStream()
                        stream.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        stream.write(body)
                        stream.flush()
                    }
                }
                action()
            }
            try {
                serve(2) { alerts.configure(true) }
                serve(2) { alerts.poll() }
                assertNull(active())
                serve(2, 1) { alerts.poll() }
                withTimeout(5_000) { while (active() == null) delay(50) }
                val notification = requireNotNull(active()).notification
                assertEquals(R.drawable.ic_shared_activity, notification.smallIcon.resId)
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Secret space"))
                manager.cancel(config.replicaId, requireNotNull(active()).id)
                withTimeout(5_000) { while (active() != null) delay(50) }
                serve(2, 1) { SharedActivityAlerts(context, config).poll() }
                assertNull(active())
            } finally {
                alerts.configure(false)
                auth.clear()
            }
        }
    }
}
