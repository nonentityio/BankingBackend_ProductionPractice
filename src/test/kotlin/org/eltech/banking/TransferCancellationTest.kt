package org.eltech.banking

import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferCancellationTest {
    private val source = BankAccount(
        accountNumber = "ELDIK-996700111222",
        clientId = "person-a",
        clientName = "Aidar",
        bankCode = "ELDIK",
        bankName = "Eldik Test Bank",
        phone = "996700111222",
        currency = "KGS",
        balance = BigDecimal("1000.00")
    )

    private val target = BankAccount(
        accountNumber = "ELDIK2-996700333444",
        clientId = "person-b",
        clientName = "Amina",
        bankCode = "ELDIK2",
        bankName = "Eldik2 Test Bank",
        phone = "996700333444",
        currency = "KGS",
        balance = BigDecimal("500.00")
    )

    @Test
    fun `cancelled transfer is final for banking backend`() {
        val transfer = transfer("CANCELLED")

        assertFalse(shouldSyncRemoteStatus(transfer))
    }

    @Test
    fun `unfinished transfer may still be synchronized`() {
        val transfer = transfer("PROCESSING")

        assertTrue(shouldSyncRemoteStatus(transfer))
    }

    @Test
    fun `cancel grace period is time limited`() {
        val recent = transfer("CREATED", createdAt = OffsetDateTime.now().minusSeconds(1))
        val old = transfer("CREATED", createdAt = OffsetDateTime.now().minusSeconds(5))

        assertTrue(recent.isInCancelGracePeriod())
        assertFalse(old.isInCancelGracePeriod())
    }

    private fun transfer(status: String, createdAt: OffsetDateTime = OffsetDateTime.now()): BankTransfer {
        return BankTransfer(
            paymentId = "payment-test",
            from = source,
            to = target,
            amount = BigDecimal("10.00"),
            currency = "KGS",
            category = "TRANSFER",
            serviceId = "transfer.internal",
            serviceRequisite = "",
            paymentStatus = status,
            applied = false,
            createdAt = createdAt
        )
    }
}
