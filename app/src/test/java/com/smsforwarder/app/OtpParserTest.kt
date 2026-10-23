package com.smsforwarder.app

import com.smsforwarder.app.otp.OtpParser
import com.smsforwarder.app.security.Auth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpParserTest {
    @Test
    fun extractsCommonOtpPhrases() {
        assertEquals("123456", OtpParser.extract("Your OTP is 123456. Do not share."))
        assertEquals("482910", OtpParser.extract("482910 is your verification code"))
        assertEquals("917544", OtpParser.extract("Code: 917544\nکد ورود اسنپ"))
    }

    @Test
    fun extractsIranianSamples() {
        assertEquals(
            "54713",
            OtpParser.extract(
                """دیجی‌کالا
کد تایید: 54713
کد محرمانه است.

ThrUzH7IYAS"""
            )
        )
        assertEquals(
            "917544",
            OtpParser.extract(
                """Code: 917544
کد ورود اسنپ
برای دیگران نفرستید

jU4m+Zli/TH"""
            )
        )
        assertEquals(
            "49707",
            OtpParser.extract(
                """nilcloth
کد تائید: 49707
لغو11"""
            )
        )
        assertEquals(
            "96212",
            OtpParser.extract(
                """<#> کد تایید یک زن: 96212

vSnmZVinueq

لغو11"""
            )
        )
        assertEquals(
            "305727",
            OtpParser.extract(
                """Code: 305727
کد ثبت‌ نام در سانگ‌ سرا
برای دیگران نفرستید
لغو11"""
            )
        )
        assertEquals(
            "8451",
            OtpParser.extract(
                """<#>
کد ورود به گهواره: 8451




nm+DGLPBW2M
لغو11"""
            )
        )
        assertEquals(
            "39462",
            OtpParser.extract(
                """Code: 39462
کد یکبار مصرف همراه‌من
3tfvy9PD6wO"""
            )
        )
        assertEquals(
            "194880",
            OtpParser.extract(
                """دیجی‌پی
«محرمانه»
از در اختیار قرار دادن محتوای این پیامک به دیگران، اکیدا خودداری کنید.
کد احراز هویت جهت ورود: 
Code: 194880
 
T0DIulhRSNL"""
            )
        )
        assertEquals(
            "250733",
            OtpParser.extract(
                """کدتایید شما 250733
rojinkala.com
لغو11"""
            )
        )
        assertEquals(
            "772454",
            OtpParser.extract("کد تایید مربوط به سامانه ازدواج : 772454")
        )
    }

    @Test
    fun ignoresHashOnlyNoise() {
        assertNull(OtpParser.extract("ThrUzH7IYAS\nvSnmZVinueq\nلغو11"))
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
