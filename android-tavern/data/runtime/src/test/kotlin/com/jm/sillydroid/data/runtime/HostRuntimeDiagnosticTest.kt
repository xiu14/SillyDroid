package com.jm.sillydroid.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class HostRuntimeDiagnosticTest {

    @Test
    fun `selinux diagnostic maps enforce file values`() {
        assertEquals("Enforcing", selinuxStateFromEnforceFile("1\n"))
        assertEquals("Permissive", selinuxStateFromEnforceFile("0"))
    }

    @Test
    fun `selinux diagnostic hides unreadable enforce file`() {
        assertEquals("unavailable", selinuxStateFromEnforceFile(null))
    }

    @Test
    fun `selinux diagnostic hides blank or unexpected content`() {
        assertEquals("unavailable", selinuxStateFromEnforceFile(""))
        assertEquals("unavailable", selinuxStateFromEnforceFile("Permission denied"))
    }
}
