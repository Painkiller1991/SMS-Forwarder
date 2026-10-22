package com.smsforwarder.app.otp

object OtpParser {
    private val patterns = listOf(
        Regex("""(?i)(?:otp|code|pin|password|passcode|verification|verify)[^\d]{0,20}(\d{4,8})"""),
        Regex("""(?i)(\d{4,8})\s*(?:is your|as your)?\s*(?:otp|code|pin)"""),
        Regex("""\b(\d{6})\b""")
    )

    fun extract(body: String): String? {
        for (pattern in patterns) {
            val match = pattern.find(body) ?: continue
            val code = match.groupValues.getOrNull(1) ?: continue
            if (code.length in 4..8) return code
        }
        return null
    }
}
