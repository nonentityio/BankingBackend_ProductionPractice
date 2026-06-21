package org.eltech.banking

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.handler.BodyHandler
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class BankingApplicationVerticle : AbstractVerticle() {
    private val clients = linkedMapOf<String, DemoClient>()
    private val accounts = linkedMapOf<String, DemoAccount>()
    private val transfers = linkedMapOf<String, DemoTransfer>()
    private lateinit var paymentClient: PaymentServiceClient

    override fun start(startPromise: Promise<Void>) {
        seedData()
        paymentClient = PaymentServiceClient(
            WebClient.create(vertx),
            System.getenv("PAYMENT_SERVICE_URL") ?: "http://localhost:8080"
        )

        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())
        router.get("/health").handler(::health)
        router.post("/clients/register").handler(::registerClient)
        router.get("/clients/:clientId/accounts").handler(::listClientAccounts)
        router.get("/accounts/:accountNumber").handler(::getAccount)
        router.post("/transfers").handler(::createTransfer)
        router.get("/transfers/:paymentId").handler(::getTransfer)
        router.post("/transfers/:paymentId/cancel").handler(::cancelTransfer)

        val port = System.getenv("PORT")?.toIntOrNull() ?: 8090
        vertx.createHttpServer()
            .requestHandler(router)
            .listen(port)
            .map<Void> { null }
            .onSuccess {
                println("Banking API listening on port $port")
                startPromise.complete()
            }
            .onFailure(startPromise::fail)
    }

    private fun seedData() {
        addClient("client-a", "Aidar", "BANKA-100-200", BigDecimal("5000.00"))
        addClient("client-b", "Amina", "BANKB-300-400", BigDecimal("2500.00"))
        addClient("client-timeout", "Timeout Receiver", "TIMEOUT-300-400", BigDecimal("0.00"))
        addClient("client-hold", "Hold Receiver", "HOLD-300-400", BigDecimal("0.00"))
        addClient("merchant-mobile", "Mobile Operator", "MOBILE-100-001", BigDecimal("0.00"))
        addClient("merchant-utility", "Utility Provider", "UTILITY-200-001", BigDecimal("0.00"))
        addClient("merchant-internet", "Internet Provider", "INTERNET-300-001", BigDecimal("0.00"))
        addClient("merchant-card", "Card Service", "CARD-400-001", BigDecimal("0.00"))
        addClient("merchant-wallet", "Wallet Service", "WALLET-500-001", BigDecimal("0.00"))
    }

    private fun addClient(clientId: String, name: String, accountNumber: String, balance: BigDecimal) {
        clients[clientId] = DemoClient(clientId, name)
        accounts[accountNumber] = DemoAccount(accountNumber, clientId, "KGS", balance)
    }

    private fun health(ctx: RoutingContext) {
        ctx.json(JsonObject().put("status", "UP").put("service", "BankingBackend"))
    }

    private fun registerClient(ctx: RoutingContext) {
        val body = ctx.body().asJsonObject() ?: JsonObject()
        val name = body.getString("clientName")?.trim().orEmpty()
        if (name.isBlank()) {
            fail(ctx, 400, "clientName is required")
            return
        }

        val clientId = "client-" + UUID.randomUUID().toString().take(8)
        val accountNumber = "BANK-" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
        addClient(clientId, name, accountNumber, BigDecimal("1000.00"))

        ctx.response().setStatusCode(201).putHeader("content-type", "application/json")
            .end(accountJson(accounts.getValue(accountNumber)).put("clientName", name).encode())
    }

    private fun listClientAccounts(ctx: RoutingContext) {
        val clientId = ctx.pathParam("clientId")
        val items = accounts.values.filter { it.clientId == clientId }.map(::accountJson)
        ctx.json(JsonObject().put("items", items))
    }

    private fun getAccount(ctx: RoutingContext) {
        val account = accounts[ctx.pathParam("accountNumber")]
        if (account == null) {
            fail(ctx, 404, "account not found")
            return
        }
        ctx.json(accountJson(account))
    }

    private fun createTransfer(ctx: RoutingContext) {
        val body = ctx.body().asJsonObject() ?: JsonObject()
        val fromAccount = body.getString("fromAccount")?.trim().orEmpty()
        val toAccount = body.getString("toAccount")?.trim().orEmpty()
        val currency = body.getString("currency")?.trim()?.uppercase() ?: "KGS"
        val amount = parseAmount(body)

        val source = accounts[fromAccount]
        val target = accounts[toAccount]
        if (source == null) {
            fail(ctx, 404, "source account not found")
            return
        }
        if (target == null) {
            fail(ctx, 404, "target account not found")
            return
        }
        if (amount == null) {
            fail(ctx, 400, "amount is invalid")
            return
        }
        if (source.currency != currency || target.currency != currency) {
            fail(ctx, 400, "currency mismatch")
            return
        }
        if (source.balance < amount) {
            fail(ctx, 409, "not enough money")
            return
        }

        paymentClient.createPayment(source.clientId, toAccount, amount, currency).onSuccess { payment ->
            val transfer = DemoTransfer(
                paymentId = payment.getString("paymentId"),
                fromAccount = fromAccount,
                toAccount = toAccount,
                amount = amount,
                currency = currency,
                paymentStatus = payment.getString("status"),
                applied = false
            )
            transfers[transfer.paymentId] = transfer
            ctx.response().setStatusCode(202).putHeader("content-type", "application/json")
                .end(transferJson(transfer).encode())
        }.onFailure {
            fail(ctx, 502, it.message ?: "payment service unavailable")
        }
    }

    private fun getTransfer(ctx: RoutingContext) {
        val paymentId = ctx.pathParam("paymentId")
        val transfer = transfers[paymentId]
        if (transfer == null) {
            fail(ctx, 404, "transfer not found")
            return
        }

        syncTransfer(transfer).onSuccess {
            ctx.json(transferJson(transfer))
        }.onFailure {
            fail(ctx, 502, it.message ?: "payment service unavailable")
        }
    }

    private fun cancelTransfer(ctx: RoutingContext) {
        val paymentId = ctx.pathParam("paymentId")
        val transfer = transfers[paymentId]
        if (transfer == null) {
            fail(ctx, 404, "transfer not found")
            return
        }
        if (transfer.applied) {
            fail(ctx, 409, "money already transferred")
            return
        }

        paymentClient.cancelPayment(paymentId).onSuccess {
            transfer.paymentStatus = "CANCELLED"
            ctx.json(transferJson(transfer))
        }.onFailure {
            fail(ctx, 502, it.message ?: "payment service unavailable")
        }
    }

    private fun syncTransfer(transfer: DemoTransfer): Future<Void> {
        return paymentClient.getPayment(transfer.paymentId).map { payment ->
            val status = payment.getString("status")
            transfer.paymentStatus = status
            if (status == "SUCCESS" && !transfer.applied) {
                val source = accounts.getValue(transfer.fromAccount)
                val target = accounts.getValue(transfer.toAccount)
                source.balance = source.balance.subtract(transfer.amount)
                target.balance = target.balance.add(transfer.amount)
                transfer.applied = true
            }
            null
        }
    }

    private fun parseAmount(body: JsonObject): BigDecimal? {
        val raw = body.getValue("amount")
        val amount = if (raw is Number) {
            BigDecimal(raw.toString())
        } else if (raw is String) {
            raw.toBigDecimalOrNull()
        } else {
            null
        }
        if (amount == null || amount <= BigDecimal.ZERO) {
            return null
        }
        return amount.setScale(2, RoundingMode.HALF_UP)
    }

    private fun accountJson(account: DemoAccount): JsonObject {
        val client = clients[account.clientId]
        return JsonObject()
            .put("accountNumber", account.accountNumber)
            .put("clientId", account.clientId)
            .put("clientName", client?.clientName ?: account.clientId)
            .put("currency", account.currency)
            .put("balance", account.balance.toPlainString())
    }

    private fun transferJson(transfer: DemoTransfer): JsonObject {
        return JsonObject()
            .put("paymentId", transfer.paymentId)
            .put("fromAccount", transfer.fromAccount)
            .put("toAccount", transfer.toAccount)
            .put("amount", transfer.amount.toPlainString())
            .put("currency", transfer.currency)
            .put("paymentStatus", transfer.paymentStatus)
            .put("applied", transfer.applied)
            .put("from", accountJson(accounts.getValue(transfer.fromAccount)))
            .put("to", accountJson(accounts.getValue(transfer.toAccount)))
    }

    private fun fail(ctx: RoutingContext, status: Int, message: String) {
        ctx.response()
            .setStatusCode(status)
            .putHeader("content-type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }
}

class PaymentServiceClient(
    private val webClient: WebClient,
    private val baseUrl: String
) {
    fun createPayment(clientId: String, toAccount: String, amount: BigDecimal, currency: String): Future<JsonObject> {
        val body = JsonObject()
            .put("clientId", clientId)
            .put("providerId", "demo-provider")
            .put("amount", amount.toPlainString())
            .put("currency", currency)
            .put("requisite", toAccount)

        return webClient.postAbs("$baseUrl/payments")
            .putHeader("Authorization", "Bearer demo-token")
            .putHeader("Idempotency-Key", "banking-" + UUID.randomUUID())
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(body)
            .compose { response ->
                if (response.statusCode() in 200..299) {
                    Future.succeededFuture(response.bodyAsJsonObject())
                } else {
                    Future.failedFuture(response.bodyAsJsonObject()?.getString("error") ?: "payment failed")
                }
            }
    }

    fun getPayment(paymentId: String): Future<JsonObject> {
        return webClient.getAbs("$baseUrl/payments/$paymentId")
            .putHeader("Authorization", "Bearer demo-token")
            .send()
            .compose { response ->
                if (response.statusCode() in 200..299) {
                    Future.succeededFuture(response.bodyAsJsonObject())
                } else {
                    Future.failedFuture(response.bodyAsJsonObject()?.getString("error") ?: "payment not found")
                }
            }
    }

    fun cancelPayment(paymentId: String): Future<JsonObject> {
        return webClient.postAbs("$baseUrl/payments/$paymentId/cancel")
            .putHeader("Authorization", "Bearer demo-token")
            .send()
            .compose { response ->
                if (response.statusCode() in 200..299) {
                    Future.succeededFuture(response.bodyAsJsonObject())
                } else {
                    Future.failedFuture(response.bodyAsJsonObject()?.getString("error") ?: "cancel failed")
                }
            }
    }
}

data class DemoClient(
    val clientId: String,
    val clientName: String
)

data class DemoAccount(
    val accountNumber: String,
    val clientId: String,
    val currency: String,
    var balance: BigDecimal
)

data class DemoTransfer(
    val paymentId: String,
    val fromAccount: String,
    val toAccount: String,
    val amount: BigDecimal,
    val currency: String,
    var paymentStatus: String,
    var applied: Boolean
)
