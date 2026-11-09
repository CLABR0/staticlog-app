package com.demo.zt.compat

/** 反射访问 android.os.SystemProperties（隐藏 API）。 */
object SystemProperties {
    fun get(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String).orEmpty()
    }.getOrDefault("")

    fun getInt(key: String, def: Int): Int = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
        m.invoke(null, key, def) as Int
    }.getOrDefault(def)

    fun getBoolean(key: String, def: Boolean): Boolean = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        m.invoke(null, key, def) as Boolean
    }.getOrDefault(def)
}
