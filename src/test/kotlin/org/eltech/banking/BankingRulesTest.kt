package org.eltech.banking

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BankingRulesTest {
    private val source = BankAccount(
        accountNumber = "ELDIK-996700111222",
        clientId = "person-a",
        clientName = "Aidar",
        bankCode = "ELDIK",
        bankName = "Eldik Test Bank",
        phone = "996700111222",
        currency = "KGS",
        balance = BigDecimal("100.00")
    )

    private val target = BankAccount(
        accountNumber = "ELDIK2-996700333444",
        clientId = "person-b",
        clientName = "Amina",
        bankCode = "ELDIK2",
        bankName = "Eldik2 Test Bank",
        phone = "996700333444",
        currency = "KGS",
        balance = BigDecimal("10.00")
    )

    @Test
    fun `normalizes phone for lookup`() {
        assertEquals("996700111222", BankingRules.normalizePhone("+996 700 111 222"))
    }

    @Test
    fun `parses money with two decimals`() {
        assertEquals(BigDecimal("10.24"), BankingRules.parseAmount("10.235"))
        assertNull(BankingRules.parseAmount("0"))
        assertNull(BankingRules.parseAmount("abc"))
    }


    @Test
    fun `hashes and verifies pin codes`() {
        val hash = BankingSecurity.hashPin("1234")

        assertTrue(hash.startsWith("sha256$"))
        assertTrue(BankingSecurity.verifyPin("1234", hash))
        assertFalse(BankingSecurity.verifyPin("0000", hash))
        assertTrue(BankingSecurity.pinLooksValid("1234"))
        assertFalse(BankingSecurity.pinLooksValid("12ab"))
    }

    @Test
    fun `routes bank and service payments to operation organizations`() {
        assertEquals("eldik-test-bank", BankingRules.operationClientFor("ELDIK"))
        assertEquals("eldik2-test-bank", BankingRules.providerFor("ELDIK2", "TRANSFER"))
        assertEquals("merchant-network", BankingRules.providerFor("MERCHANT", "UTILITY"))
    }

    @Test
    fun `validates transfer constraints`() {
        BankingRules.validateTransfer(source, target, BigDecimal("10.00"), "KGS")

        assertFailsWith<IllegalArgumentException> {
            BankingRules.validateTransfer(source, target.copy(currency = "USD"), BigDecimal("10.00"), "KGS")
        }
        assertFailsWith<IllegalArgumentException> {
            BankingRules.validateTransfer(source, target, BigDecimal("150.00"), "KGS")
        }
        assertFailsWith<IllegalArgumentException> {
            BankingRules.validateTransfer(source, source, BigDecimal("10.00"), "KGS")
        }
    }
}
