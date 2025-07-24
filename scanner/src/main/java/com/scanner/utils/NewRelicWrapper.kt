package com.scanner.utils

import com.newrelic.agent.android.NewRelic
import com.newrelic.agent.android.logging.LogLevel
import org.json.JSONObject

object NewRelicWrapper {

    fun logError(message: String) {
        try {

            NewRelic.log(LogLevel.ERROR, message)
        } catch (_: Exception) {
        }
    }

    fun logDebug(message: String) {
        try {

            NewRelic.log(LogLevel.WARN, message)
        } catch (_: Exception) {
        }
    }

    fun logInfo(message: String) {
        NewRelic.log(LogLevel.INFO, message)
    }

    fun logCustom(message: JSONObject) {
        try {

            val map = mutableMapOf<String, Any>()
            for (key in message.keys()) {
                map[key] = message.get(key)
            }
            NewRelic.recordCustomEvent("CustomEvent", map)
        } catch (_: Exception) {
        }
    }

}