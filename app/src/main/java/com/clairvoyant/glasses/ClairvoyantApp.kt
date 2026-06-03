package com.clairvoyant.glasses

import android.app.Application

class ClairvoyantApp : Application() {

    companion object {
        /** The claude.ai/code base URL for remote sessions. */
        const val CLAUDE_CODE_BASE_URL = "https://claude.ai/code"

        /** Validate that a scanned URL is a legitimate Claude Code session URL. */
        fun isValidClaudeCodeUrl(url: String): Boolean {
            return url.startsWith("https://claude.ai/code") ||
                   url.startsWith("https://claude.ai/chat") ||
                   url.startsWith("https://claude.ai/")
        }
    }
}
