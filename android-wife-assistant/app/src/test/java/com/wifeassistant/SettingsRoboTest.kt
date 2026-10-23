package com.wifeassistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wifeassistant.data.Recipient
import com.wifeassistant.data.SenderAccount
import com.wifeassistant.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// اختبار حفظ/قراءة الإعدادات على الـ JVM عبر Robolectric — بيحرس دوران JSON للموديلات
// الجديدة (لغة الشخص + حسابات المُرسِل + إعداد Business API) اللي R8 ممكن يكسرها.
// ملاحظة: مابنلمسش groqKey لأنه على Android Keystore (مش متاح تحت Robolectric).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRoboTest {
    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test fun recipientsRoundTripIncludingLanguage() {
        val s = Settings(ctx())
        s.recipients = listOf(
            Recipient(id = "r1", name = "أحمد", relation = "partner_wife", language = "ar"),
            Recipient(id = "r2", name = "John", relation = "friend", language = "en"),
        )
        val back = s.recipients
        assertEquals(2, back.size)
        assertEquals("en", back.first { it.id == "r2" }.language)
        assertEquals("ar", back.first { it.id == "r1" }.language)
    }

    @Test fun senderAccountsRoundTrip() {
        val s = Settings(ctx())
        s.senderAccounts = listOf(
            SenderAccount(id = "s1", label = "الأعمال", channel = "whatsapp_business", countryCode = "971", signature = "— فريق وصال"),
            SenderAccount(id = "s2", label = "مصر", channel = "whatsapp", countryCode = "20"),
        )
        s.selectedSenderId = "s1"
        val back = s.senderAccounts
        assertEquals(2, back.size)
        val biz = back.first { it.id == "s1" }
        assertEquals("whatsapp_business", biz.channel)
        assertEquals("971", biz.countryCode)
        assertEquals("— فريق وصال", biz.signature)
        assertEquals("s1", s.selectedSenderId)
    }

    @Test fun businessApiConfigPersists() {
        val s = Settings(ctx())
        s.businessApiEndpoint = "  https://x.vercel.app/api/send  "
        s.businessApiKey = "  secret123  "
        assertEquals("https://x.vercel.app/api/send", s.businessApiEndpoint) // اتشال منها الفراغ
        assertEquals("secret123", s.businessApiKey)
    }

    @Test fun defaultsAreSafeWhenEmpty() {
        val s = Settings(ctx())
        assertTrue(s.senderAccounts.isEmpty())
        assertEquals("", s.businessApiEndpoint)
        assertEquals("", s.selectedSenderId)
        assertTrue(s.mutedOccasions.isEmpty())
    }

    @Test fun aiNoticeAckDefaultsFalseAndPersists() {
        val s = Settings(ctx())
        assertEquals(false, s.aiNoticeAck)
        s.aiNoticeAck = true
        assertEquals(true, s.aiNoticeAck)
    }

    @Test fun mutedOccasionsRoundTrip() {
        val s = Settings(ctx())
        s.mutedOccasions = listOf("r1|عيد ميلاد", "رمضان")
        val back = s.mutedOccasions.toSet()
        assertTrue(back.contains("r1|عيد ميلاد"))
        assertTrue(back.contains("رمضان"))
    }
    @Test fun upgradePreservesRetiredModelUntilExplicitChoice() {
        val s = Settings(ctx())
        s.model = "llama-3.3-70b-versatile"
        assertEquals("llama-3.3-70b-versatile", s.model)
        assertEquals(false, s.groqEnabled)
        assertEquals(false, s.backgroundSuggestions)
    }

    @Test fun freshInstallDoesNotInventAPartnerAndLegacyDataMigrates() {
        val s = Settings(ctx())
        s.ensureSeed()
        assertTrue(s.recipients.isEmpty())
        s.wifeName = "Fictional person"
        s.ensureSeed()
        assertEquals("Fictional person", s.recipients.single().name)
        assertEquals("partner_wife", s.recipients.single().relation)
    }
}
