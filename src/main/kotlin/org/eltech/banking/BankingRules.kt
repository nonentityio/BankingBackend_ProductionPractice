package org.eltech.banking

import java.math.BigDecimal
import java.math.RoundingMode

object BankingRules {
    private val servicePaymentCategories = setOf("MOBILE_TOPUP", "UTILITY", "CARD_PAYMENT", "WALLET")
    private val utilityServices = mapOf(
        "utility.electricity" to ServiceRule("EL", "электроэнергия"),
        "utility.water" to ServiceRule("WATER", "вода"),
        "utility.gas" to ServiceRule("GAS", "газ"),
        "utility.heating" to ServiceRule("HEAT", "отопление"),
        "utility.trash" to ServiceRule("TRASH", "вывоз мусора"),
        "utility.rent" to ServiceRule("RENT", "квартплата"),
        "utility.general" to ServiceRule("UTIL", "коммунальная услуга"),
        "internet.home" to ServiceRule("NET", "домашний интернет"),
        "internet.failed" to ServiceRule("NET-FAIL", "тестовая ошибка интернета")
    )

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
            "UTILITY" -> "utility.general"
            "CARD_PAYMENT" -> "card.repayment"
            "WALLET" -> "wallet.topup"
            else -> "transfer.internal"
        }
    }

    fun validateServicePayment(category: String, serviceId: String, requisite: String, amount: BigDecimal) {
        if (category !in servicePaymentCategories) return
        if (requisite.isBlank()) throw IllegalArgumentException("service requisite is required")
        if (category == "UTILITY") validateUtilityPayment(serviceId, requisite)
        if (category in setOf("MOBILE_TOPUP", "WALLET") && amount > BigDecimal("100000.00")) {
            throw IllegalArgumentException("amount exceeds 100000.00 limit for top-up service")
        }
    }

    fun normalizeServiceRequisite(category: String, serviceId: String, raw: String): String {
        val value = raw.trim()
        return when (category.uppercase()) {
            "MOBILE_TOPUP" -> normalizeServicePhone(value)
            "UTILITY" -> normalizeUtilityRequisite(serviceId, value)
            "CARD_PAYMENT" -> value.filter(Char::isDigit)
            "WALLET" -> {
                val payload = value.uppercase().removePrefix("WAL-").filter(Char::isLetterOrDigit)
                "WAL-$payload"
            }
            else -> value
        }
    }

    fun utilityServiceName(serviceId: String): String? {
        return utilityServices[serviceId.lowercase()]?.title
    }

    private fun validateUtilityPayment(serviceId: String, requisite: String) {
        val normalizedServiceId = serviceId.lowercase()
        val rule = utilityServices[normalizedServiceId]
            ?: throw IllegalArgumentException("unknown utility service")
        val normalizedRequisite = requisite.trim().uppercase()
        val allowed = normalizedRequisite.startsWith("${rule.prefix}-") ||
            normalizedRequisite.startsWith("${rule.prefix}:") ||
            normalizedRequisite.filter { it.isLetterOrDigit() }.length >= 6
        if (!allowed) throw IllegalArgumentException("${rule.title} requisite is invalid")
    }

    private fun normalizeServicePhone(value: String): String {
        val digits = value.filter(Char::isDigit)
        return when {
            digits.startsWith("996") -> digits
            digits.length == 10 && digits.startsWith("0") -> "996${digits.drop(1)}"
            digits.length == 9 -> "996$digits"
            else -> digits
        }
    }

    private fun normalizeUtilityRequisite(serviceId: String, value: String): String {
        val rule = utilityServices[serviceId.lowercase()] ?: return value.uppercase()
        val upper = value.uppercase().replace(':', '-')
        val expectedPrefix = "${rule.prefix}-"
        if (upper.startsWith(expectedPrefix)) return expectedPrefix + upper.removePrefix(expectedPrefix).filter(Char::isDigit)
        return expectedPrefix + upper.filter(Char::isDigit)
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

private data class ServiceRule(
    val prefix: String,
    val title: String
)
