package net.corda.testing.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.net.HostAndPort
import java.net.URL

class HttpApi(val root: URL, val mapper: ObjectMapper = defaultMapper) {
    /**
     * Send a PUT with a payload to the path on the API specified.
     *
     * @param data String values are assumed to be valid JSON. All other values will be mapped to JSON.
     */
    fun putJson(path: String, data: Any = Unit) = HttpUtils.putJson(URL(root, path), toJson(data))

    /**
     * Send a POST with a payload to the path on the API specified.
     *
     * @param data String values are assumed to be valid JSON. All other values will be mapped to JSON.
     */
    fun postJson(path: String, data: Any = Unit) = HttpUtils.postJson(URL(root, path), toJson(data))

    /**
     * Send a GET request to the path on the API specified.
     */
    inline fun <reified T : Any> getJson(path: String, params: Map<String, String> = mapOf()) = HttpUtils.getJson<T>(URL(root, path), params, mapper)

    private fun toJson(any: Any) = any as? String ?: HttpUtils.defaultMapper.writeValueAsString(any)

    companion object {
        fun fromHostAndPort(hostAndPort: HostAndPort, base: String, protocol: String = "http", mapper: ObjectMapper = defaultMapper): HttpApi
                = HttpApi(URL("$protocol://$hostAndPort/$base/"), mapper)
        private val defaultMapper: ObjectMapper by lazy {
            net.corda.jackson.JacksonSupport.createNonRpcMapper()
        }
    }
}
