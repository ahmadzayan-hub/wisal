package com.wifeassistant

import com.wifeassistant.data.AppDiagnostics
import org.junit.Assert.*
import org.junit.Test

class AppDiagnosticsTest {
    @Test fun exportedFieldsAreAnExplicitNonPersonalAllowlist() {
        val text = AppDiagnostics.render("1.0.4", 4, "com.wisal.app", 35, "a".repeat(64))
        assertTrue(text.contains("1.0.4 (4)")); assertTrue(text.contains("com.wisal.app"))
        assertEquals(6, text.lines().size)
        assertEquals(listOf("render"), AppDiagnostics::class.java.declaredMethods.map { it.name })
        assertFalse(text.contains("Bearer")); assertFalse(text.contains("gsk_"))
    }
    @Test fun missingCertificateIsHonestNotInvented() {
        assertTrue(AppDiagnostics.render("1.0", 1, "com.wisal.app", 26, null).contains("unavailable"))
    }
}
