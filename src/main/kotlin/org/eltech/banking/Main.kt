package org.eltech.banking

import io.vertx.core.Vertx

fun main() {
    Vertx.vertx().deployVerticle(BankingApplicationVerticle())
        .onSuccess { println("BankingBackend started") }
        .onFailure { error ->
            error.printStackTrace()
            System.exit(1)
        }
}
