package com.star.wgauto

import android.app.Application

class WgApp : Application() {
    lateinit var store: Store
    lateinit var engine: Engine
    lateinit var ovpn: OvpnEngine

    override fun onCreate() {
        super.onCreate()
        // عملية Xray منفصلة: لا نُنشئ فيها محرّك WireGuard (Go runtime ثانٍ) ولا التخزين.
        if (isXrayProcess()) return
        store = Store(this)
        engine = Engine(this, store)
        ovpn = OvpnEngine(this, store)
    }

    private fun isXrayProcess(): Boolean = try {
        val raw = java.io.File("/proc/self/cmdline").readBytes()
        String(raw, Charsets.UTF_8).split('\u0000').firstOrNull().orEmpty().endsWith(":xray")
    } catch (e: Exception) {
        false
    }
}
