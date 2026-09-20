package com.star.wgauto

import android.app.Application

class WgApp : Application() {
    lateinit var store: Store
    lateinit var engine: Engine

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        engine = Engine(this, store)
    }
}
