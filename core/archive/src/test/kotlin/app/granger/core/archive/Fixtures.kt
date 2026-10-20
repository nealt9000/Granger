package app.granger.core.archive

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Parses a recorded `/metadata/<id>` fixture through the real client, with no network. */
internal fun fixtureItem(id: String, resource: String): ArchiveItem {
    val body = object {}.javaClass.classLoader!!.getResourceAsStream(resource)!!.readBytes().decodeToString()
    val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("")
            .code(200).body(body.toResponseBody()).build()
    }).build()
    return runBlocking { ArchiveOrgClient(http, "Granger/1.0 (+test)").item(id) }
}

internal const val HOLST = "holst-the-planets-holst-london-symphony-orchestra-1922-1924"
