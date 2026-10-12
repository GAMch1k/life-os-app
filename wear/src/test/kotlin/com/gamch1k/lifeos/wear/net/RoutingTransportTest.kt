package com.gamch1k.lifeos.wear.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RoutingTransportTest {
    private val request = ApiRequest("GET", "/api/tasks/v1/tasks")

    private class Counting(private val result: ApiResult) : ApiTransport {
        var calls = 0
        override suspend fun execute(request: ApiRequest): ApiResult {
            calls++
            return result
        }
    }

    private fun response(status: Int, route: Route) = ApiResult.Response(status, ByteArray(0), route)

    @Test
    fun `the phone answers first, and its answer is final whatever the status`() = runTest {
        for (status in listOf(200, 401, 503)) {
            val phone = Counting(response(status, Route.PHONE))
            val direct = Counting(response(200, Route.DIRECT))
            val result = RoutingTransport(phone, direct).execute(request) as ApiResult.Response
            assertEquals(Route.PHONE, result.route)
            assertEquals(status, result.status)
            assertEquals(0, direct.calls)
        }
    }

    @Test
    fun `without an answer through the phone the watch goes direct`() = runTest {
        for (reason in listOf(Unavailability.NO_PHONE, Unavailability.PHONE_NO_RELAY, Unavailability.NETWORK, Unavailability.NO_CREDENTIALS)) {
            val direct = Counting(response(200, Route.DIRECT))
            val result = RoutingTransport(Counting(ApiResult.Unavailable(reason)), direct).execute(request)
            assertEquals(Route.DIRECT, (result as ApiResult.Response).route)
            assertEquals(1, direct.calls)
        }
    }

    @Test
    fun `a refused request is not sent again directly`() = runTest {
        val refused = ApiResult.Unavailable(Unavailability.PROTOCOL, "forbidden")
        val direct = Counting(response(200, Route.DIRECT))
        assertSame(refused, RoutingTransport(Counting(refused), direct).execute(request))
        assertEquals(0, direct.calls)
    }

    @Test
    fun `when both fail the more useful reason is reported`() = runTest {
        val directNoToken = ApiResult.Unavailable(Unavailability.NO_CREDENTIALS)
        assertSame(directNoToken, RoutingTransport(Counting(ApiResult.Unavailable(Unavailability.NO_PHONE)), Counting(directNoToken)).execute(request))
        val phoneOffline = ApiResult.Unavailable(Unavailability.NETWORK, "phone offline")
        assertSame(phoneOffline, RoutingTransport(Counting(phoneOffline), Counting(ApiResult.Unavailable(Unavailability.NETWORK))).execute(request))
    }
}
