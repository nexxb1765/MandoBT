package com.mandobt

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) {
            sp = c.applicationContext.getSharedPreferences("mando", Context.MODE_PRIVATE)
        }
    }

    var mouseSens: Int
        get() = sp.getInt("ms", 50)
        set(v) { sp.edit().putInt("ms", v).apply() }

    var stickSens: Int
        get() = sp.getInt("ss", 50)
        set(v) { sp.edit().putInt("ss", v).apply() }

    var deadzone: Int
        get() = sp.getInt("dz", 20)
        set(v) { sp.edit().putInt("dz", v).apply() }

    var scrollSpeed: Int
        get() = sp.getInt("sc", 50)
        set(v) { sp.edit().putInt("sc", v).apply() }

    var haptics: Boolean
        get() = sp.getBoolean("hp", true)
        set(v) { sp.edit().putBoolean("hp", v).apply() }

    var lastMac: String?
        get() = sp.getString("mac", null)
        set(v) { sp.edit().putString("mac", v).apply() }

    var preset: String
        get() = sp.getString("preset", "Xbox") ?: "Xbox"
        set(v) { sp.edit().putString("preset", v).apply() }

    fun layout(name: String): String? = sp.getString("layout_$name", null)

    fun saveLayout(name: String, json: String?) {
        val e = sp.edit()
        if (json == null) e.remove("layout_$name") else e.putString("layout_$name", json)
        e.apply()
    }
}
