package com.smsforwarder.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Properties

class DesktopPrefs {
    private val dir: Path = Path.of(System.getProperty("user.home"), ".sms-forwarder-pc")
    private val file: Path = dir.resolve("prefs.properties")
    private val props = Properties()

    init {
        Files.createDirectories(dir)
        if (Files.exists(file)) {
            Files.newInputStream(file).use { props.load(it) }
        }
        val existing = props.getProperty("sharedSecret", "")
        if (existing.length < 16) {
            props.setProperty("sharedSecret", generateSecret())
            save()
        }
    }

    var sharedSecret: String
        get() = props.getProperty("sharedSecret", "")
        set(value) {
            props.setProperty("sharedSecret", value)
        }

    var port: Int
        get() = props.getProperty("port", "18765").toIntOrNull() ?: 18765
        set(value) {
            props.setProperty("port", value.toString())
        }

    fun save() {
        Files.createDirectories(dir)
        Files.newOutputStream(file).use { props.store(it, "SMS Forwarder PC") }
    }

    fun pairingPayload(localIp: String): String =
        "smsfw://pair?ip=$localIp&port=$port&s=$sharedSecret"

    companion object {
        fun generateSecret(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
