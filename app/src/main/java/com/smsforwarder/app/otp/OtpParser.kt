package com.smsforwarder.app.otp

/**
 * Language-agnostic OTP extractor: scores 4–8 digit candidates instead of
 * hardcoding every locale phrase.
 */
object OtpParser {
    private val candidateRegex = Regex("""(?<!\d)(\d{4,8})(?!\d)""")
    private val latinLabel = Regex(
        """(?i)(?:otp|code|pin|passcode|password|verification|verify)"""
    )
    private const val FA_CODE_STEM = "کد"
    private val yearLike = Regex("""^(?:19|20)\d{2}$""")

    fun extract(body: String): String? {
        if (body.isBlank()) return null
        var best: Pair<String, Int>? = null
        for (match in candidateRegex.findAll(body)) {
            val code = match.groupValues[1]
            val score = score(body, match.range.first, code)
            if (score < 2) continue
            val current = best
            if (current == null ||
                score > current.second ||
                (score == current.second && match.range.first < body.indexOf(current.first))
            ) {
                best = code to score
            }
        }
        return best?.first
    }

    private fun score(body: String, index: Int, code: String): Int {
        var s = 0
        val end = index + code.length
        val before = body.substring((index - 40).coerceAtLeast(0), index)
        val after = body.substring(end, (end + 40).coerceAtMost(body.length))
        if (latinLabel.containsMatchIn(before) ||
            latinLabel.containsMatchIn(after) ||
            before.contains(FA_CODE_STEM) ||
            after.contains(FA_CODE_STEM)
        ) {
            s += 4
        }
        val prev = before.trimEnd()
        if (prev.endsWith(":") || prev.endsWith("：") || prev.endsWith("=")) {
            s += 3
        }
        if (code.length == 5 || code.length == 6) {
            s += 2
        } else if (code.length in 4..8) {
            s += 1
        }
        val leftOk = index == 0 || !body[index - 1].isLetterOrDigit()
        val rightOk = end >= body.length || !body[end].isLetterOrDigit()
        if (leftOk && rightOk) s += 1
        if (yearLike.matches(code)) s -= 3
        return s
    }
}
