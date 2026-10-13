package com.star.wgauto

import android.app.Application

class WgApp : Application() {
    lateinit var store: Store
    lateinit var engine: Engine
    lateinit var ovpn: OvpnEngine

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        engine = Engine(this, store)
        ovpn = OvpnEngine(this, store)
    }
}
