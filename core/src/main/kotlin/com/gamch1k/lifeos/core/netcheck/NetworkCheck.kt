package com.gamch1k.lifeos.core.netcheck

import android.content.Context
import com.gamch1k.lifeos.core.config.LifeOsEndpoints
import com.gamch1k.lifeos.core.secrets.ServiceToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chromium.net.CronetEngine
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * The network check (P18-01): can the app reach Life OS on a network that
 * resets TLS handshakes carrying our hostname in clear SNI?
 *
 * Runs the probes one after another, so their timings do not disturb each
 * other, each with a fresh Cronet engine (cold DNS cache, new connections).
 */
class NetworkCheck(context: Context) {
    private val context = context.applicationContext

    suspend fun run(appVersion: String, quicAllowed: Boolean, token: ServiceToken?): NetworkCheckResult =
        withContext(Dispatchers.IO) {
            val executor = Executors.newSingleThreadExecutor()
            try {
                val startedAt = ZonedDateTime.now()
                val embeddedVersion = CronetEngines.provider(context, CronetEngines.Provider.EMBEDDED)?.version

                // 1 and 4: embedded Cronet, then the service-token request on the same engine.
                var authenticated: HttpProbeResult? = null
                val cronetLabel = "Cronet ${embeddedVersion ?: "?"}, embedded, built-in DNS"
                val cronet = withEngine(CronetEngines.Provider.EMBEDDED, quicAllowed, cronetLabel) { engine ->
                    val trace = CronetProbe.get(engine, executor, cronetLabel, LifeOsEndpoints.TRACE_URL, parseTrace = true)
                    if (token != null) {
                        authenticated = CronetProbe.get(
                            engine,
                            executor,
                            "Service token request",
                            LifeOsEndpoints.TASKS_PROBE_URL,
                            headers = mapOf(
                                "CF-Access-Client-Id" to token.clientId,
                                "CF-Access-Client-Secret" to token.clientSecret,
                                "X-Life-OS-Actor" to "user",
                            ),
                            parseTrace = false,
                        )
                    }
                    trace
                } ?: unavailable(cronetLabel)

                // Extra: Android's own Cronet, when the device has it.
                val httpEngineVersion = CronetEngines.provider(context, CronetEngines.Provider.HTTP_ENGINE)?.version
                val httpEngine = httpEngineVersion?.let { version ->
                    val label = "HttpEngine $version, Android's own Cronet, built-in DNS (extra)"
                    withEngine(CronetEngines.Provider.HTTP_ENGINE, quicAllowed, label) { engine ->
                        CronetProbe.get(engine, executor, label, LifeOsEndpoints.TRACE_URL, parseTrace = true)
                    }
                }

                // 2: the platform stack, which sends the hostname in clear.
                val platform = PlatformHttpProbe.get(LifeOsEndpoints.TRACE_URL)

                // 3: device facts and the HTTPS record as the system resolver sees it.
                val device = DeviceFactsCollector.collect(context, embeddedVersion)
                val dns = SystemDnsProbe.httpsRecord(
                    DeviceFactsCollector.activeNetwork(context),
                    LifeOsEndpoints.HOST,
                    executor,
                )

                NetworkCheckResult(
                    startedAt = startedAt,
                    appVersion = appVersion,
                    quicAllowed = quicAllowed,
                    device = device,
                    cronet = cronet,
                    httpEngine = httpEngine,
                    platform = platform,
                    dns = dns,
                    authenticated = authenticated,
                )
            } finally {
                executor.shutdown()
            }
        }

    /**
     * Builds an engine, runs [block], and always shuts the engine down. Null when
     * the provider is missing; a provider that throws becomes a failed result, so
     * one broken stack never hides the others.
     */
    private suspend fun withEngine(
        provider: CronetEngines.Provider,
        quicAllowed: Boolean,
        label: String,
        block: suspend (CronetEngine) -> HttpProbeResult,
    ): HttpProbeResult? {
        val engine = try {
            CronetEngines.build(context, provider, quicAllowed) ?: return null
        } catch (error: RuntimeException) {
            return unavailable(label, "could not start: ${error.javaClass.simpleName}: ${error.message}")
        }
        return try {
            block(engine)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            unavailable(label, "failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { engine.shutdown() }
        }
    }

    private fun unavailable(label: String, why: String = "provider not available") =
        HttpProbeResult(label, LifeOsEndpoints.TRACE_URL, null, null, 0, null, null, null, why)
}
