package com.gamch1k.lifeos.core.api

import com.gamch1k.lifeos.core.secrets.ServiceToken
import java.io.ByteArrayOutputStream

/** A scripted [HttpTransport]: answers in order, and remembers what was asked. */
class FakeTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    val bodies = mutableListOf<ByteArray?>()
    private val answers = ArrayDeque<() -> HttpResponse>()

    fun json(status: Int, body: String, headers: Map<String, List<String>> = mapOf("content-type" to listOf("application/json"))) = apply {
        answers += { HttpResponse(status, headers, body.toByteArray()) }
    }

    fun html(status: Int, body: String = "<html>error</html>") = apply {
        answers += { HttpResponse(status, mapOf("content-type" to listOf("text/html")), body.toByteArray()) }
    }

    fun redirect(status: Int, location: String) = apply {
        answers += { HttpResponse(status, mapOf("location" to listOf(location)), ByteArray(0), location) }
    }

    fun fail(error: TransportException) = apply { answers += { throw error } }

    override suspend fun execute(request: HttpRequest, sink: ResponseSink?, onProgress: ((Long) -> Unit)?): HttpResponse {
        requests += request
        bodies += request.body?.open()?.use { it.readBytes() }
        val answer = answers.removeFirstOrNull()?.invoke() ?: error("no answer scripted for ${request.method} ${request.url}")
        if (sink != null && answer.status in 200..299) {
            val out = sink.open(answer.status, answer.headers) ?: return answer
            out.use { it.write(answer.body) }
            return HttpResponse(answer.status, answer.headers, ByteArray(0))
        }
        return answer
    }

    companion object {
        val TOKEN = ServiceToken("0123456789abcdef0123456789abcdef.access", "cfast_secretsecretsecret")
        val CONNECTION = Connection("https://lifeos.example.com", TOKEN)

        fun client(transport: FakeTransport, connection: Connection? = CONNECTION, sleeps: MutableList<Long> = mutableListOf()) =
            LifeOsClient(transport, { connection }, userAgent = "LifeOS-Android/test", sleep = { sleeps += it })
    }
}

fun ByteArray.text() = toString(Charsets.UTF_8)

fun ByteArrayOutputStream.text() = toByteArray().text()
