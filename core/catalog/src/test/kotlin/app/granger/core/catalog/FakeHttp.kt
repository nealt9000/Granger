package app.granger.core.catalog

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** Answers requests from [routes] without touching the network; unknown URLs get a 404. */
class FakeHttp : Interceptor {
    val routes = mutableMapOf<String, ByteArray>()
    var offline = false
    val requested = mutableListOf<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requested += request.url.toString()
        if (offline) throw IOException("offline")
        val body = routes[request.url.toString()]
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body != null) 200 else 404)
            .message("")
            .body((body ?: ByteArray(0)).toResponseBody())
            .build()
    }

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
}
