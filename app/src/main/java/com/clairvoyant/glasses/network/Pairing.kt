package com.clairvoyant.glasses.network

/** One relay address the glasses can dial: ws:// (tls=false) or wss:// (tls=true). */
data class Endpoint(val host: String, val port: Int, val tls: Boolean)

/**
 * The relay pairing payload encoded in the dashboard QR:
 *   clairvoyant://pair?host=<ip>&port=<port>&token=<token>[&fhost=<h>&fport=<p>&ftls=<1|0>]
 *
 * host/port is the direct LAN endpoint; the optional f* params describe a public fallback
 * (e.g. Tailscale Funnel). Parsing is pure (no android.net.Uri) so it is JVM unit-testable.
 * Query values here are plain (hostnames, ints, a base64url token), so we split manually
 * rather than URL-decode.
 */
data class Pairing(
    val host: String,
    val port: Int,
    val token: String,
    val fallback: Endpoint? = null,
) {
    /** Dial order: LAN first, then the fallback. */
    val endpoints: List<Endpoint>
        get() = listOfNotNull(Endpoint(host, port, false), fallback)

    companion object {
        private const val PREFIX = "clairvoyant://pair?"

        fun parse(raw: String): Pairing? {
            val s = raw.trim()
            if (!s.startsWith(PREFIX)) return null
            val query = s.substring(PREFIX.length)
            val params = HashMap<String, String>()
            for (pair in query.split("&")) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                params[pair.substring(0, eq)] = pair.substring(eq + 1)
            }
            val host = params["host"]?.takeIf { it.isNotEmpty() } ?: return null
            val port = params["port"]?.toIntOrNull() ?: return null
            val token = params["token"]?.takeIf { it.isNotEmpty() } ?: return null

            val fhost = params["fhost"]?.takeIf { it.isNotEmpty() }
            val fport = params["fport"]?.toIntOrNull()
            val fallback = if (fhost != null && fport != null) {
                Endpoint(fhost, fport, params["ftls"] == "1" || params["ftls"] == "true")
            } else null

            return Pairing(host, port, token, fallback)
        }
    }
}
