package com.clairvoyant.glasses.network

/**
 * The relay pairing payload encoded in the dashboard QR:
 *   clairvoyant://pair?host=<ip>&port=<port>&token=<token>
 *
 * Parsing is pure (no android.net.Uri) so it is JVM unit-testable. Query values here are
 * plain (IPv4, an int, and a base64url token), so we split manually rather than URL-decode.
 */
data class Pairing(val host: String, val port: Int, val token: String) {
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
            return Pairing(host, port, token)
        }
    }
}
