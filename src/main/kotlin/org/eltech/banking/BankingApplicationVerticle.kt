package org.eltech.banking

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.net.URI
import java.util.UUID

class BankingApplicationVerticle : AbstractVerticle() {
    private lateinit var db: Pool
    private lateinit var paymentClient: PaymentServiceClient

    override fun start(startPromise: Promise<Void>) {
        db = createPgPool()
        paymentClient = PaymentServiceClient(
            WebClient.create(vertx),
            System.getenv("PAYMENT_SERVICE_URL") ?: "http://localhost:8080",
            System.getenv("PAYMENT_SERVICE_TOKEN") ?: "local-dev-payment-token"
        )

        setupDatabase()
            .compose { startHttpServer() }
            .onSuccess { startPromise.complete() }
            .onFailure(startPromise::fail)
    }

    private fun startHttpServer(): Future<Void> {
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())
        router.get("/health").handler(::health)
        router.get("/banks").handler(::banks)
        router.post("/auth/login").handler(::login)
        router.post("/clients/register").handler(::registerClient)
        router.get("/clients/:clientId/accounts").handler(::listClientAccounts)
        router.get("/clients/:clientId/transfers").handler(::listClientTransfers)
        router.get("/accounts/:accountNumber").handler(::getAccount)
        router.get("/directory/accounts").handler(::findAccount)
        router.post("/transfers").handler(::createTransfer)
        router.get("/transfers/:paymentId").handler(::getTransfer)
        router.post("/transfers/:paymentId/cancel").handler(::cancelTransfer)

        val port = System.getenv("PORT")?.toIntOrNull() ?: 8090
        return vertx.createHttpServer()
            .requestHandler(router)
            .listen(port)
            .map<Void> { null }
            .onSuccess { println("Banking API listening on port $port") }
    }

    private fun setupDatabase(): Future<Void> {
        val statements = listOf(
            """
            create table if not exists bank_clients (
                client_id varchar(80) primary key,
                phone varchar(32) not null unique,
                full_name varchar(160) not null,
                pin_code varchar(128) not null,
                created_at timestamptz not null default now()
            )
            """.trimIndent(),
            """
            create table if not exists bank_accounts (
                account_number varchar(80) primary key,
                client_id varchar(80) not null references bank_clients(client_id),
                bank_code varchar(40) not null,
                bank_name varchar(120) not null,
                phone varchar(32) not null,
                currency varchar(3) not null default 'KGS',
                balance numeric(18, 2) not null,
                account_kind varchar(30) not null default 'CLIENT',
                created_at timestamptz not null default now(),
                unique (phone, bank_code)
            )
            """.trimIndent(),
            """
            create table if not exists bank_transfers (
                payment_id varchar(80) primary key,
                from_account varchar(80) not null references bank_accounts(account_number),
                to_account varchar(80) not null references bank_accounts(account_number),
                receiver_phone varchar(32) not null,
                receiver_bank varchar(40) not null,
                category varchar(40) not null,
                service_id varchar(80) not null default 'transfer.internal',
                service_requisite varchar(160) not null default '',
                amount numeric(18, 2) not null,
                currency varchar(3) not null,
                payment_status varchar(40) not null,
                applied boolean not null default false,
                created_at timestamptz not null default now(),
                updated_at timestamptz not null default now()
            )
            """.trimIndent(),
            "alter table bank_clients alter column pin_code type varchar(128)",
            "alter table bank_transfers add column if not exists service_id varchar(80) not null default 'transfer.internal'",
            "alter table bank_transfers add column if not exists service_requisite varchar(160) not null default ''",
            "create index if not exists idx_bank_accounts_phone_bank on bank_accounts (phone, bank_code)",
            "create index if not exists idx_bank_transfers_from_created on bank_transfers (from_account, created_at desc)",
            "create index if not exists idx_bank_transfers_to_created on bank_transfers (to_account, created_at desc)"
        )

        var chain: Future<Void> = Future.succeededFuture()
        statements.forEach { sql ->
            chain = chain.compose { db.query(sql).execute().map<Void> { null } }
        }
        return chain.compose { seedMockData() }
    }

    private fun seedMockData(): Future<Void> {
        val clients = listOf(
            MockClient("person-aidar", "996700111222", "Aidar Saparov", "1111"),
            MockClient("person-amina", "996700333444", "Amina Tulegenova", "2222"),
            MockClient("person-daniyar", "996700555666", "Daniyar Ibraev", "3333"),
            MockClient("merchant-mobile", "996700000101", "Mobile Operator", "0000"),
            MockClient("merchant-mobile-plus", "996700000102", "Mobile Plus", "0000"),
            MockClient("merchant-mobile-lite", "996700000103", "Mobile Lite", "0000"),
            MockClient("merchant-utility", "996700000202", "Utility Provider", "0000"),
            MockClient("merchant-internet", "996700000303", "Internet Provider", "0000"),
            MockClient("merchant-card", "996700000404", "Card Service", "0000"),
            MockClient("merchant-wallet", "996700000505", "Wallet Service", "0000"),
            MockClient("demo-hold", "996700000606", "Hold Demo Receiver", "0000")
        )
        val accounts = listOf(
            MockAccount("ELDIK-996700111222", "person-aidar", "ELDIK", "Eldik Test Bank", "996700111222", BigDecimal("5000.00"), "CLIENT"),
            MockAccount("ELDIK-996700333444", "person-amina", "ELDIK", "Eldik Test Bank", "996700333444", BigDecimal("2500.00"), "CLIENT"),
            MockAccount("ELDIK2-996700333444", "person-amina", "ELDIK2", "Eldik2 Test Bank", "996700333444", BigDecimal("1800.00"), "CLIENT"),
            MockAccount("ELDIK2-996700555666", "person-daniyar", "ELDIK2", "Eldik2 Test Bank", "996700555666", BigDecimal("3000.00"), "CLIENT"),
            MockAccount("MERCH-996700000101", "merchant-mobile", "MERCHANT", "Merchant Network", "996700000101", BigDecimal("0.00"), "MERCHANT"),
            MockAccount("MERCH-996700000102", "merchant-mobile-plus", "MERCHANT", "Merchant Network", "996700000102", BigDecimal("0.00"), "MERCHANT"),
            MockAccount("MERCH-996700000103", "merchant-mobile-lite", "MERCHANT", "Merchant Network", "996700000103", BigDecimal("0.00"), "MERCHANT"),
            MockAccount("MERCH-996700000202", "merchant-utility", "MERCHANT", "Merchant Network", "996700000202", BigDecimal("0.00"), "MERCHANT"),
            MockAccount("MERCH-996700000303", "merchant-internet", "MERCHANT", "Merchant Network", "996700000303", BigDecimal("0.00"), "MERCHANT"),
            MockAccount("MERCH-996700000404", "merchant-card", "MERCHANT", "Merchant Network", "996700000404", BigDecimal("0.00"), "MERCHANT"),
            MockAccount("MERCH-996700000505", "merchant-wallet", "MERCHANT", "Merchant Network", "996700000505", BigDecimal("0.00"), "MERCHANT"),
            MockAccount("HOLD-996700000606", "demo-hold", "DEMO", "Demo Bank", "996700000606", BigDecimal("0.00"), "MERCHANT")
        )

        var chain: Future<Void> = Future.succeededFuture()
        clients.forEach { client ->
            chain = chain.compose {
                db.preparedQuery(
                    """
                    insert into bank_clients (client_id, phone, full_name, pin_code)
                    values ($1, $2, $3, $4)
                    on conflict (client_id) do nothing
                    """.trimIndent()
                ).execute(Tuple.of(client.clientId, client.phone, client.fullName, BankingSecurity.hashPin(client.pin))).map<Void> { null }
            }
        }
        accounts.forEach { account ->
            chain = chain.compose {
                db.preparedQuery(
                    """
                    insert into bank_accounts (account_number, client_id, bank_code, bank_name, phone, balance, account_kind)
                    values ($1, $2, $3, $4, $5, $6, $7)
                    on conflict (account_number) do nothing
                    """.trimIndent()
                ).execute(
                    Tuple.of(
                        account.accountNumber,
                        account.clientId,
                        account.bankCode,
                        account.bankName,
                        account.phone,
                        account.balance,
                        account.kind
                    )
                ).map<Void> { null }
            }
        }
        return chain
    }

    private fun health(ctx: RoutingContext) {
        db.query("select 1").execute()
            .onSuccess { ctx.json(JsonObject().put("status", "UP").put("service", "BankingBackend").put("storage", "postgres")) }
            .onFailure { fail(ctx, 503, it.message ?: "database unavailable") }
    }

    private fun banks(ctx: RoutingContext) {
        ctx.json(
            JsonObject().put(
                "items",
                JsonArray()
                    .add(JsonObject().put("code", "ELDIK").put("name", "Eldik Test Bank"))
                    .add(JsonObject().put("code", "ELDIK2").put("name", "Eldik2 Test Bank"))
                    .add(JsonObject().put("code", "MERCHANT").put("name", "Merchant Network"))
                    .add(JsonObject().put("code", "DEMO").put("name", "Demo Bank"))
            )
        )
    }

    private fun login(ctx: RoutingContext) {
        val body = ctx.body().asJsonObject() ?: JsonObject()
        val phone = normalizePhone(body.getString("phone"))
        val pin = body.getString("pin")?.trim().orEmpty()
        if (phone.isBlank() || pin.isBlank()) {
            fail(ctx, 400, "phone and pin are required")
            return
        }

        db.preparedQuery("select client_id, phone, full_name, pin_code from bank_clients where phone = $1")
            .execute(Tuple.of(phone))
            .compose { rows ->
                val client = rows.firstOrNull()
                if (client == null || !BankingSecurity.verifyPin(pin, client.getString("pin_code"))) {
                    Future.failedFuture<JsonObject>(IllegalArgumentException("invalid phone or pin"))
                } else {
                    val clientId = client.getString("client_id")
                    val storedPin = client.getString("pin_code")
                    val migratePin: Future<Void> = if (BankingSecurity.isHashed(storedPin)) {
                        Future.succeededFuture()
                    } else {
                        db.preparedQuery("update bank_clients set pin_code = $1 where client_id = $2")
                            .execute(Tuple.of(BankingSecurity.hashPin(pin), clientId))
                            .mapEmpty()
                    }
                    migratePin.compose {
                        accountsByClient(clientId).map { accounts ->
                            JsonObject()
                                .put("clientId", clientId)
                                .put("phone", formatPhone(client.getString("phone")))
                                .put("clientName", client.getString("full_name"))
                                .put("accounts", JsonArray(accounts))
                        }
                    }
                }
            }
            .onSuccess { ctx.json(it) }
            .onFailure { fail(ctx, 401, it.message ?: "login failed") }
    }

    private fun registerClient(ctx: RoutingContext) {
        val body = ctx.body().asJsonObject() ?: JsonObject()
        val name = body.getString("clientName")?.trim().orEmpty()
        val phone = normalizePhone(body.getString("phone"))
        val pin = body.getString("pin")?.trim().orEmpty().ifBlank { "1111" }
        val bankCode = body.getString("bankCode")?.trim()?.uppercase() ?: "ELDIK"
        val bankName = bankName(bankCode)
        if (name.isBlank() || phone.isBlank()) {
            fail(ctx, 400, "clientName and phone are required")
            return
        }
        if (!BankingSecurity.pinLooksValid(pin)) {
            fail(ctx, 400, "pin must contain 4 to 6 digits")
            return
        }

        val clientId = "person-" + UUID.randomUUID().toString().take(8)
        val accountNumber = "$bankCode-$phone"
        db.preparedQuery(
            "insert into bank_clients (client_id, phone, full_name, pin_code) values ($1, $2, $3, $4)"
        ).execute(Tuple.of(clientId, phone, name, BankingSecurity.hashPin(pin)))
            .compose {
                db.preparedQuery(
                    """
                    insert into bank_accounts (account_number, client_id, bank_code, bank_name, phone, balance)
                    values ($1, $2, $3, $4, $5, $6)
                    """.trimIndent()
                ).execute(Tuple.of(accountNumber, clientId, bankCode, bankName, phone, BigDecimal("1000.00")))
            }
            .compose { getAccountByNumber(accountNumber) }
            .onSuccess { ctx.response().setStatusCode(201).putHeader("content-type", "application/json").end(accountJson(it).encode()) }
            .onFailure { fail(ctx, 409, it.message ?: "registration failed") }
    }

    private fun listClientAccounts(ctx: RoutingContext) {
        accountsByClient(ctx.pathParam("clientId"))
            .onSuccess { ctx.json(JsonObject().put("items", JsonArray(it))) }
            .onFailure { fail(ctx, 500, it.message ?: "accounts unavailable") }
    }

    private fun listClientTransfers(ctx: RoutingContext) {
        transfersByClient(ctx.pathParam("clientId"))
            .compose(::syncTransfers)
            .onSuccess { ctx.json(JsonObject().put("items", JsonArray(it.map(::transferJson)))) }
            .onFailure { fail(ctx, 500, it.message ?: "transfers unavailable") }
    }

    private fun getAccount(ctx: RoutingContext) {
        getAccountByNumber(ctx.pathParam("accountNumber"))
            .onSuccess { ctx.json(accountJson(it)) }
            .onFailure { fail(ctx, 404, it.message ?: "account not found") }
    }

    private fun findAccount(ctx: RoutingContext) {
        val phone = normalizePhone(ctx.queryParam("phone").firstOrNull())
        val bank = ctx.queryParam("bank").firstOrNull()?.trim()?.uppercase().orEmpty()
        if (phone.isBlank() || bank.isBlank()) {
            fail(ctx, 400, "phone and bank are required")
            return
        }
        findAccountByPhoneAndBank(phone, bank)
            .onSuccess { ctx.json(accountJson(it)) }
            .onFailure { fail(ctx, 404, it.message ?: "receiver account not found") }
    }

    private fun createTransfer(ctx: RoutingContext) {
        val body = ctx.body().asJsonObject() ?: JsonObject()
        val fromAccount = body.getString("fromAccount")?.trim().orEmpty()
        val toAccountRaw = body.getString("toAccount")?.trim().orEmpty()
        val receiverPhone = normalizePhone(body.getString("receiverPhone"))
        val receiverBank = body.getString("receiverBank")?.trim()?.uppercase().orEmpty()
        val currency = body.getString("currency")?.trim()?.uppercase() ?: "KGS"
        val category = body.getString("category")?.trim()?.uppercase() ?: "TRANSFER"
        val serviceId = body.getString("serviceId")?.trim().orEmpty().ifBlank { BankingRules.defaultServiceId(category) }
        val serviceRequisite = body.getString("serviceRequisite")?.trim().orEmpty()
        val amount = parseAmount(body)

        if (fromAccount.isBlank() || amount == null) {
            fail(ctx, 400, "fromAccount and amount are required")
            return
        }

        val targetFuture = if (toAccountRaw.isNotBlank()) {
            getAccountByNumber(toAccountRaw)
        } else {
            findAccountByPhoneAndBank(receiverPhone, receiverBank)
        }

        getAccountByNumber(fromAccount)
            .compose { source ->
                targetFuture.compose { target ->
                    validateTransfer(source, target, amount, currency)
                    BankingRules.validateServicePayment(category, serviceId, serviceRequisite, amount)
                    val operationClientId = operationClientFor(source.bankCode)
                    val providerId = providerFor(target.bankCode, category)
                    val paymentRequisite = serviceRequisite.ifBlank { target.accountNumber }
                    paymentClient.createPayment(operationClientId, providerId, paymentRequisite, amount, currency, category, serviceId)
                        .compose { payment ->
                            val paymentId = payment.getString("paymentId")
                            insertTransfer(paymentId, source, target, amount, currency, category, serviceId, serviceRequisite, payment.getString("status"))
                                .compose { getTransferById(paymentId) }
                        }
                }
            }
            .onSuccess { ctx.response().setStatusCode(202).putHeader("content-type", "application/json").end(transferJson(it).encode()) }
            .onFailure { fail(ctx, 409, it.message ?: "transfer failed") }
    }

    private fun getTransfer(ctx: RoutingContext) {
        getTransferById(ctx.pathParam("paymentId"))
            .compose { syncTransfer(it) }
            .onSuccess { ctx.json(transferJson(it)) }
            .onFailure { fail(ctx, 404, it.message ?: "transfer not found") }
    }

    private fun cancelTransfer(ctx: RoutingContext) {
        getTransferById(ctx.pathParam("paymentId"))
            .compose { transfer ->
                if (transfer.applied) {
                    Future.failedFuture(IllegalArgumentException("money already transferred"))
                } else {
                    paymentClient.cancelPayment(transfer.paymentId)
                        .compose {
                            db.preparedQuery("update bank_transfers set payment_status = 'CANCELLED', updated_at = now() where payment_id = $1")
                                .execute(Tuple.of(transfer.paymentId))
                        }
                        .compose { getTransferById(transfer.paymentId) }
                }
            }
            .onSuccess { ctx.json(transferJson(it)) }
            .onFailure { fail(ctx, 409, it.message ?: "cancel failed") }
    }

    private fun syncTransfer(transfer: BankTransfer): Future<BankTransfer> {
        return paymentClient.getPayment(transfer.paymentId).compose { payment ->
            val status = payment.getString("status")
            if (status == "SUCCESS" && !transfer.applied) {
                db.withTransaction { tx ->
                    tx.preparedQuery("update bank_accounts set balance = balance - $1 where account_number = $2")
                        .execute(Tuple.of(transfer.amount, transfer.from.accountNumber))
                        .compose {
                            tx.preparedQuery("update bank_accounts set balance = balance + $1 where account_number = $2")
                                .execute(Tuple.of(transfer.amount, transfer.to.accountNumber))
                        }
                        .compose {
                            tx.preparedQuery("update bank_transfers set payment_status = $1, applied = true, updated_at = now() where payment_id = $2")
                                .execute(Tuple.of(status, transfer.paymentId))
                        }
                        .map<Void> { null }
                }.compose { getTransferById(transfer.paymentId) }
            } else if (status != transfer.paymentStatus) {
                db.preparedQuery("update bank_transfers set payment_status = $1, updated_at = now() where payment_id = $2")
                    .execute(Tuple.of(status, transfer.paymentId))
                    .compose { getTransferById(transfer.paymentId) }
            } else {
                Future.succeededFuture(transfer)
            }
        }
    }

    private fun syncTransfers(items: List<BankTransfer>): Future<List<BankTransfer>> {
        var chain: Future<List<BankTransfer>> = Future.succeededFuture(emptyList())
        items.forEach { item ->
            chain = chain.compose { synced ->
                val next = if (item.paymentStatus == "SUCCESS" || item.paymentStatus == "FAILED" || item.paymentStatus == "CANCELLED") {
                    Future.succeededFuture(item)
                } else {
                    syncTransfer(item)
                }
                next.map { synced + it }
            }
        }
        return chain
    }

    private fun validateTransfer(source: BankAccount, target: BankAccount, amount: BigDecimal, currency: String) {
        BankingRules.validateTransfer(source, target, amount, currency)
    }

    private fun accountsByClient(clientId: String): Future<List<JsonObject>> {
        return db.preparedQuery(
            """
            select a.*, c.full_name from bank_accounts a
            join bank_clients c on c.client_id = a.client_id
            where a.client_id = $1
              and a.bank_code not in ('CITY', 'NOVA')
            order by case
                when a.bank_code = 'ELDIK' then 0
                when a.bank_code = 'ELDIK2' then 1
                when a.bank_code = 'MERCHANT' then 2
                else 9
            end, a.account_number
            """.trimIndent()
        ).execute(Tuple.of(clientId)).map { rows -> rows.map { accountJson(accountFromRow(it)) } }
    }

    private fun getAccountByNumber(accountNumber: String): Future<BankAccount> {
        return db.preparedQuery(
            """
            select a.*, c.full_name from bank_accounts a
            join bank_clients c on c.client_id = a.client_id
            where a.account_number = $1
              and a.bank_code not in ('CITY', 'NOVA')
            """.trimIndent()
        ).execute(Tuple.of(accountNumber)).map { rows ->
            rows.firstOrNull()?.let(::accountFromRow) ?: throw IllegalArgumentException("account not found")
        }
    }

    private fun findAccountByPhoneAndBank(phone: String, bankCode: String): Future<BankAccount> {
        return db.preparedQuery(
            """
            select a.*, c.full_name from bank_accounts a
            join bank_clients c on c.client_id = a.client_id
            where a.phone = $1 and a.bank_code = $2
              and a.bank_code not in ('CITY', 'NOVA')
            """.trimIndent()
        ).execute(Tuple.of(phone, bankCode)).map { rows ->
            rows.firstOrNull()?.let(::accountFromRow) ?: throw IllegalArgumentException("receiver account not found")
        }
    }

    private fun insertTransfer(
        paymentId: String,
        source: BankAccount,
        target: BankAccount,
        amount: BigDecimal,
        currency: String,
        category: String,
        serviceId: String,
        serviceRequisite: String,
        status: String
    ): Future<Void> {
        return db.preparedQuery(
            """
            insert into bank_transfers (
                payment_id, from_account, to_account, receiver_phone, receiver_bank,
                category, service_id, service_requisite, amount, currency, payment_status, applied
            )
            values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, false)
            """.trimIndent()
        ).execute(
            Tuple.of(
                paymentId,
                source.accountNumber,
                target.accountNumber,
                target.phone,
                target.bankCode,
                category,
                serviceId,
                serviceRequisite,
                amount,
                currency,
                status
            )
        ).map<Void> { null }
    }

    private fun getTransferById(paymentId: String): Future<BankTransfer> {
        return db.preparedQuery(
            """
            select
                t.payment_id,
                t.amount,
                t.currency,
                t.category,
                t.service_id,
                t.service_requisite,
                t.payment_status,
                t.applied,
                fa.account_number as from_account_number,
                fa.client_id as from_client_id,
                fa.bank_code as from_bank_code,
                fa.bank_name as from_bank_name,
                fa.phone as from_phone,
                fa.currency as from_currency,
                fa.balance as from_balance,
                fc.full_name as from_client_name,
                ta.account_number as to_account_number,
                ta.client_id as to_client_id,
                ta.bank_code as to_bank_code,
                ta.bank_name as to_bank_name,
                ta.phone as to_phone,
                ta.currency as to_currency,
                ta.balance as to_balance,
                tc.full_name as to_client_name
            from bank_transfers t
            join bank_accounts fa on fa.account_number = t.from_account
            join bank_clients fc on fc.client_id = fa.client_id
            join bank_accounts ta on ta.account_number = t.to_account
            join bank_clients tc on tc.client_id = ta.client_id
            where t.payment_id = $1
            """.trimIndent()
        ).execute(Tuple.of(paymentId)).map { rows ->
            rows.firstOrNull()?.let(::transferFromRow) ?: throw IllegalArgumentException("transfer not found")
        }
    }

    private fun transfersByClient(clientId: String): Future<List<BankTransfer>> {
        return db.preparedQuery(
            """
            select
                t.payment_id,
                t.amount,
                t.currency,
                t.category,
                t.service_id,
                t.service_requisite,
                t.payment_status,
                t.applied,
                fa.account_number as from_account_number,
                fa.client_id as from_client_id,
                fa.bank_code as from_bank_code,
                fa.bank_name as from_bank_name,
                fa.phone as from_phone,
                fa.currency as from_currency,
                fa.balance as from_balance,
                fc.full_name as from_client_name,
                ta.account_number as to_account_number,
                ta.client_id as to_client_id,
                ta.bank_code as to_bank_code,
                ta.bank_name as to_bank_name,
                ta.phone as to_phone,
                ta.currency as to_currency,
                ta.balance as to_balance,
                tc.full_name as to_client_name
            from bank_transfers t
            join bank_accounts fa on fa.account_number = t.from_account
            join bank_clients fc on fc.client_id = fa.client_id
            join bank_accounts ta on ta.account_number = t.to_account
            join bank_clients tc on tc.client_id = ta.client_id
            where (fa.client_id = $1 or ta.client_id = $1)
              and fa.bank_code not in ('CITY', 'NOVA')
              and ta.bank_code not in ('CITY', 'NOVA')
            order by t.created_at desc
            limit 50
            """.trimIndent()
        ).execute(Tuple.of(clientId)).map { rows -> rows.map(::transferFromRow) }
    }

    private fun accountJson(account: BankAccount): JsonObject {
        return JsonObject()
            .put("accountNumber", account.accountNumber)
            .put("clientId", account.clientId)
            .put("clientName", account.clientName)
            .put("phoneNumber", formatPhone(account.phone))
            .put("bankCode", account.bankCode)
            .put("bankName", account.bankName)
            .put("currency", account.currency)
            .put("balance", account.balance.toPlainString())
    }

    private fun transferJson(transfer: BankTransfer): JsonObject {
        return JsonObject()
            .put("paymentId", transfer.paymentId)
            .put("fromAccount", transfer.from.accountNumber)
            .put("toAccount", transfer.to.accountNumber)
            .put("receiverPhone", formatPhone(transfer.to.phone))
            .put("receiverBank", transfer.to.bankCode)
            .put("category", transfer.category)
            .put("serviceId", transfer.serviceId)
            .put("serviceRequisite", transfer.serviceRequisite)
            .put("amount", transfer.amount.toPlainString())
            .put("currency", transfer.currency)
            .put("paymentStatus", transfer.paymentStatus)
            .put("applied", transfer.applied)
            .put("transferType", if (transfer.from.bankCode == transfer.to.bankCode) "INTERNAL" else "INTERBANK")
            .put("from", accountJson(transfer.from))
            .put("to", accountJson(transfer.to))
    }

    private fun accountFromRow(row: Row): BankAccount {
        return BankAccount(
            accountNumber = row.getString("account_number"),
            clientId = row.getString("client_id"),
            clientName = row.getString("full_name"),
            bankCode = row.getString("bank_code"),
            bankName = row.getString("bank_name"),
            phone = row.getString("phone"),
            currency = row.getString("currency"),
            balance = row.getBigDecimal("balance")
        )
    }

    private fun transferFromRow(row: Row): BankTransfer {
        val source = BankAccount(
            accountNumber = row.getString("from_account_number"),
            clientId = row.getString("from_client_id"),
            clientName = row.getString("from_client_name"),
            bankCode = row.getString("from_bank_code"),
            bankName = row.getString("from_bank_name"),
            phone = row.getString("from_phone"),
            currency = row.getString("from_currency"),
            balance = row.getBigDecimal("from_balance")
        )
        val target = BankAccount(
            accountNumber = row.getString("to_account_number"),
            clientId = row.getString("to_client_id"),
            clientName = row.getString("to_client_name"),
            bankCode = row.getString("to_bank_code"),
            bankName = row.getString("to_bank_name"),
            phone = row.getString("to_phone"),
            currency = row.getString("to_currency"),
            balance = row.getBigDecimal("to_balance")
        )
        return BankTransfer(
            paymentId = row.getString("payment_id"),
            from = source,
            to = target,
            amount = row.getBigDecimal("amount"),
            currency = row.getString("currency"),
            category = row.getString("category"),
            serviceId = row.getString("service_id"),
            serviceRequisite = row.getString("service_requisite"),
            paymentStatus = row.getString("payment_status"),
            applied = row.getBoolean("applied")
        )
    }

    private fun createPgPool(): Pool {
        val databaseUrl = System.getenv("DATABASE_URL")
        val options = if (!databaseUrl.isNullOrBlank()) {
            val uri = URI(databaseUrl)
            val userInfo = uri.userInfo.split(":", limit = 2)
            PgConnectOptions()
                .setHost(uri.host)
                .setPort(if (uri.port > 0) uri.port else 5432)
                .setDatabase(uri.path.removePrefix("/"))
                .setUser(userInfo[0])
                .setPassword(userInfo.getOrElse(1) { "" })
                .setSslMode(io.vertx.pgclient.SslMode.REQUIRE)
        } else {
            PgConnectOptions()
                .setHost(System.getenv("PGHOST") ?: "localhost")
                .setPort(System.getenv("PGPORT")?.toIntOrNull() ?: 55432)
                .setDatabase(System.getenv("PGDATABASE") ?: "banking")
                .setUser(System.getenv("PGUSER") ?: "banking")
                .setPassword(System.getenv("PGPASSWORD") ?: "banking")
        }
        return PgBuilder.pool()
            .using(vertx)
            .connectingTo(options)
            .with(PoolOptions().setMaxSize(8))
            .build()
    }

    private fun parseAmount(body: JsonObject): BigDecimal? {
        return BankingRules.parseAmount(body.getValue("amount"))
    }

    private fun normalizePhone(value: String?): String {
        return BankingRules.normalizePhone(value)
    }

    private fun formatPhone(value: String): String {
        return BankingRules.formatPhone(value)
    }

    private fun bankName(code: String): String {
        return BankingRules.bankName(code)
    }

    private fun providerFor(bankCode: String, category: String): String {
        return BankingRules.providerFor(bankCode, category)
    }

    private fun operationClientFor(bankCode: String): String {
        return BankingRules.operationClientFor(bankCode)
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
    private val baseUrl: String,
    private val token: String
) {
    fun createPayment(clientId: String, providerId: String, requisite: String, amount: BigDecimal, currency: String, category: String, serviceId: String): Future<JsonObject> {
        val body = JsonObject()
            .put("clientId", clientId)
            .put("providerId", providerId)
            .put("amount", amount.toPlainString())
            .put("currency", currency)
            .put("serviceCategory", category)
            .put("serviceId", serviceId)
            .put("requisite", requisite)

        return webClient.postAbs("$baseUrl/payments")
            .putHeader("Authorization", "Bearer $token")
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
            .putHeader("Authorization", "Bearer $token")
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
            .putHeader("Authorization", "Bearer $token")
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

data class MockClient(
    val clientId: String,
    val phone: String,
    val fullName: String,
    val pin: String
)

data class MockAccount(
    val accountNumber: String,
    val clientId: String,
    val bankCode: String,
    val bankName: String,
    val phone: String,
    val balance: BigDecimal,
    val kind: String
)

data class BankAccount(
    val accountNumber: String,
    val clientId: String,
    val clientName: String,
    val bankCode: String,
    val bankName: String,
    val phone: String,
    val currency: String,
    val balance: BigDecimal
)

data class BankTransfer(
    val paymentId: String,
    val from: BankAccount,
    val to: BankAccount,
    val amount: BigDecimal,
    val currency: String,
    val category: String,
    val serviceId: String,
    val serviceRequisite: String,
    val paymentStatus: String,
    val applied: Boolean
)
