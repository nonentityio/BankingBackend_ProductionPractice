package org.eltech.banking

import java.math.BigDecimal
import java.math.RoundingMode

object BankingRules {
    fun parseAmount(raw: Any?): BigDecimal? {
        val amount = when (raw) {
            is Number -> BigDecimal(raw.toString())
            is String -> raw.toBigDecimalOrNull()
            else -> null
        }
        if (amount == null || amount <= BigDecimal.ZERO) return null
        return amount.setScale(2, RoundingMode.HALF_UP)
    }

    fun normalizePhone(value: String?): String {
        return value.orEmpty().filter { it.isDigit() }
    }

    fun formatPhone(value: String): String {
        return if (value.startsWith("996")) "+$value" else value
    }

    fun bankName(code: String): String {
        return when (code) {
            "ELDIK" -> "Eldik Test Bank"
            "ELDIK2" -> "Eldik2 Test Bank"
            "MERCHANT" -> "Merchant Network"
            "DEMO" -> "Demo Bank"
            else -> code
        }
    }

    fun providerFor(bankCode: String, category: String): String {
        return when {
            category in setOf("MOBILE_TOPUP", "UTILITY", "CARD_PAYMENT", "WALLET") -> "merchant-network"
            bankCode == "ELDIK" -> "eldik-test-bank"
            bankCode == "ELDIK2" -> "eldik2-test-bank"
            bankCode == "DEMO" -> "demo-hold"
            else -> bankCode.lowercase().ifBlank { "unknown-provider" }
        }
    }


    fun defaultServiceId(category: String): String {
        return when (category.uppercase()) {
            "MOBILE_TOPUP" -> "mobile.topup"
            "UTILITY" -> "utility.electricity"
            "CARD_PAYMENT" -> "card.repayment"
            "WALLET" -> "wallet.topup"
            else -> "transfer.internal"
        }
    }

    fun validateServicePayment(category: String, serviceId: String, requisite: String, amount: BigDecimal) {
        if (category !in setOf("MOBILE_TOPUP", "UTILITY", "CARD_PAYMENT", "WALLET")) return
        if (requisite.isBlank()) throw IllegalArgumentException("service requisite is required")
        if (category in setOf("MOBILE_TOPUP", "WALLET") && amount > BigDecimal("100000.00")) {
            throw IllegalArgumentException("amount exceeds 100000.00 limit for top-up service")
        }
    }

    fun operationClientFor(bankCode: String): String {
        return when (bankCode) {
            "ELDIK" -> "eldik-test-bank"
            "ELDIK2" -> "eldik2-test-bank"
            "MERCHANT" -> "merchant-network"
            "DEMO" -> "demo-hold"
            else -> bankCode.lowercase()
        }
    }

    fun validateTransfer(source: BankAccount, target: BankAccount, amount: BigDecimal, currency: String) {
        if (source.currency != currency || target.currency != currency) {
            throw IllegalArgumentException("currency mismatch")
        }
        if (source.balance < amount) {
            throw IllegalArgumentException("not enough money")
        }
        if (source.accountNumber == target.accountNumber) {
            throw IllegalArgumentException("cannot transfer to the same account")
        }
    }
}
