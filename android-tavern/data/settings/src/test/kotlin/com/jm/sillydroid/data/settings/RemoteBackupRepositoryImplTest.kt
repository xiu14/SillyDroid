package com.jm.sillydroid.data.settings

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteBackupRepositoryImplTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun createBackupPollsJobUntilSucceeded() = runBlocking {
        val polls = AtomicInteger()
        handle("/backup") { respond(it, 202, """{"ok":true,"job":{"id":"j1","state":"running"}}""") }
        handle("/jobs/j1") {
            if (polls.incrementAndGet() < 2) {
                respond(it, 200, """{"ok":true,"job":{"id":"j1","state":"running"}}""")
            } else {
                respond(
                    it,
                    200,
                    """{"ok":true,"job":{"id":"j1","state":"succeeded","result":{"file":"st-data-2026-10-16.tar.gz","warning":"w"}}}"""
                )
            }
        }

        val result = RemoteBackupRepositoryImpl(baseUrl).createBackup()

        assertEquals("st-data-2026-10-16.tar.gz", result.fileName)
        assertEquals("w", result.warning)
        assertEquals(2, polls.get())
    }

    @Test
    fun restoreReturnsSnapshotName() = runBlocking {
        handle("/restore") {
            respond(
                it,
                202,
                """{"ok":true,"job":{"id":"r1","state":"succeeded","result":{"restoreTargetDir":"/data","snapshot":"pre-restore-20261016-120000.tar.gz"}}}"""
            )
        }

        val result = RemoteBackupRepositoryImpl(baseUrl).restoreBackup("st-data-2026-10-15.tar.gz")

        assertEquals("/data", result.restoreTargetDir)
        assertEquals("pre-restore-20261016-120000.tar.gz", result.snapshotName)
    }

    @Test
    fun failedJobSurfacesServerError() = runBlocking {
        handle("/restore") {
            respond(it, 202, """{"ok":true,"job":{"id":"r2","state":"running"}}""")
        }
        handle("/jobs/r2") {
            respond(it, 200, """{"ok":true,"job":{"id":"r2","state":"failed","error":"backup archive is damaged"}}""")
        }

        try {
            RemoteBackupRepositoryImpl(baseUrl).restoreBackup("broken.tar.gz")
            fail("expected IOException")
        } catch (error: IOException) {
            assertEquals("backup archive is damaged", error.message)
        }
    }

    @Test
    fun busyServerRejectsWithConflictMessage() = runBlocking {
        handle("/restore") {
            respond(it, 409, """{"ok":false,"error":"another backup operation is running: backup"}""")
        }

        try {
            RemoteBackupRepositoryImpl(baseUrl).restoreBackup("st-data.tar.gz")
            fail("expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("another backup operation is running"))
        }
    }

    @Test
    fun legacySynchronousResponseIsAccepted() = runBlocking {
        handle("/backup") { respond(it, 200, """{"ok":true,"file":"st-data-legacy.tar.gz"}""") }

        val result = RemoteBackupRepositoryImpl(baseUrl).createBackup()

        assertEquals("st-data-legacy.tar.gz", result.fileName)
    }

    private fun handle(path: String, block: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            exchange.requestBody.use { it.readBytes() }
            block(exchange)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
