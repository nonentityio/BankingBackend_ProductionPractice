package org.eltech.banking

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

fun main() {
    val config = LoadConfig.fromEnv()
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .version(HttpClient.Version.HTTP_1_1)
        .build()
    val limiter = Semaphore(config.concurrency)
    val results = mutableListOf<CompletableFuture<LoadResult>>()

    val totalMs = measureTimeMillis {
        repeat(config.count) { index ->
            limiter.acquire()
            val started = System.nanoTime()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://banking-api-amirhan-2026-9931f85de8df.herokuapp.com/transfers"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(config.body(index)))
                .build()

            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle { response, error ->
                    val latencyMs = (System.nanoTime() - started) / 1_000_000
                    limiter.release()
                    if (error != null) {
                        LoadResult(false, latencyMs, 0, error.message ?: "request failed")
                    } else {
                        val ok = response.statusCode() in 200..299
                        LoadResult(ok, latencyMs, response.statusCode(), response.body().take(180))
                    }
                }
            results += future
        }
        CompletableFuture.allOf(*results.toTypedArray()).join()
    }

    val completed = results.map { it.join() }
    val success = completed.count { it.ok }
    val failed = completed.size - success
    val rps = if (totalMs == 0L) 0.0 else completed.size * 1000.0 / totalMs
    val latencies = completed.map { it.latencyMs }.sorted()
    val avg = if (latencies.isEmpty()) 0.0 else latencies.average()
    val p95 = percentile(latencies, 0.95)
    val firstError = completed.firstOrNull { !it.ok }

    println("Load test target: https://banking-api-amirhan-2026-9931f85de8df.herokuapp.com/transfers")
    println("Requests: ${completed.size}, concurrency: ${config.concurrency}")
    println("Success: $success, failed: $failed")
    println("Duration: ${totalMs}ms")
    println("RPS: ${format(rps)}")
    println("Avg latency: ${format(avg)}ms")
    println("P95 latency: ${p95}ms")
    if (firstError != null) {
        println("First error: HTTP ${firstError.statusCode} ${firstError.message}")
    }
}

private data class LoadConfig(
    val baseUrl: String,
    val count: Int,
    val concurrency: Int,
    val fromAccount: String,
    val receiverPhone: String,
    val receiverBank: String,
    val amount: String,
    val category: String
) {
    fun body(index: Int): String {
        return """
            {
              "fromAccount": "$fromAccount",
              "receiverPhone": "$receiverPhone",
              "receiverBank": "$receiverBank",
              "amount": "$amount",
              "currency": "KGS",
              "category": "$category",
              "note": "load-$index"
            }
        """.trimIndent()
    }

    companion object {
        fun fromEnv(): LoadConfig {
            val env = System.getenv()
            val count = env["LOAD_COUNT"]?.toIntOrNull()?.coerceAtLeast(1) ?: 100
            return LoadConfig(
                baseUrl = env["LOAD_BASE_URL"] ?: "http://localhost:8090",
                count = count,
                concurrency = env["LOAD_CONCURRENCY"]?.toIntOrNull()?.coerceIn(1, count) ?: minOf(32, count),
                fromAccount = env["LOAD_FROM_ACCOUNT"] ?: "ELDIK-996700111222",
                receiverPhone = env["LOAD_RECEIVER_PHONE"] ?: "+996700333444",
                receiverBank = env["LOAD_RECEIVER_BANK"] ?: "ELDIK2",
                amount = env["LOAD_AMOUNT"] ?: "1.00",
                category = env["LOAD_CATEGORY"] ?: "TRANSFER"
            )
        }
    }
}

private data class LoadResult(
    val ok: Boolean,
    val latencyMs: Long,
    val statusCode: Int,
    val message: String
)

private fun percentile(values: List<Long>, percentile: Double): Long {
    if (values.isEmpty()) return 0
    val index = ((values.size - 1) * percentile).roundToInt().coerceIn(0, values.lastIndex)
    return values[index]
}

private fun format(value: Double): String = "%.2f".format(value)
