package com.gamefibre

import android.content.SharedPreferences
import java.lang.reflect.Proxy

internal fun testPreferences(values: MutableMap<String, Any> = mutableMapOf()): SharedPreferences {
    val changes = mutableMapOf<String, Any?>()
    val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when (method.name) {
            "putString", "putStringSet", "putLong", "putBoolean" -> { changes[args!![0] as String] = args[1]; proxy }
            "remove" -> { changes[args!![0] as String] = null; proxy }
            "apply", "commit" -> {
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                changes.clear()
                if (method.name == "commit") true else null
            }
            else -> error("Unexpected editor method: ${method.name}")
        }
    } as SharedPreferences.Editor
    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString", "getStringSet", "getLong", "getBoolean" -> values[args!![0]] ?: args[1]
            "getAll" -> values.toMap()
            "edit" -> editor
            else -> error("Unexpected preferences method: ${method.name}")
        }
    } as SharedPreferences
    }

