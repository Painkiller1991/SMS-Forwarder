package com.smsforwarder.app

import com.smsforwarder.app.otp.OtpParser
import com.smsforwarder.app.security.Auth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpParserTest {
    @Test
    fun extractsCommonOtpPhrases() {
        assertEquals("123456", OtpParser.extract("Your OTP is 123456. Do not share."))
        assertEquals("482910", OtpParser.extract("482910 is your verification code"))
    }

    @Test
    fun authRoundTrip() {
        val secret = "abc123"
        val ts = System.currentTimeMillis()
        val sig = Auth.sign(secret, ts, "+15551212", "hello")
        assertTrue(Auth.verify(secret, ts, "+15551212", "hello", sig))
        assertFalse(Auth.verify(secret, ts, "+15551212", "HELLO", sig))
    }
}
