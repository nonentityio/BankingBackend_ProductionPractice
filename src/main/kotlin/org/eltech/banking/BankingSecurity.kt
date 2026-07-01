package org.eltech.banking

import java.security.MessageDigest

object BankingSecurity {
    private const val PREFIX = "sha256$"
    private const val SALT = "banking-demo-pin-v1"

    fun pinLooksValid(pin: String): Boolean {
        return pin.length in 4..6 && pin.all { it.isDigit() }
    }

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$SALT:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return PREFIX + digest
    }

    fun verifyPin(pin: String, stored: String): Boolean {
        if (stored.startsWith(PREFIX)) {
            return constantTimeEquals(hashPin(pin), stored)
        }
        return constantTimeEquals(pin, stored)
    }

    fun isHashed(stored: String): Boolean = stored.startsWith(PREFIX)

    private fun constantTimeEquals(left: String, right: String): Boolean {
        return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
    }
}
