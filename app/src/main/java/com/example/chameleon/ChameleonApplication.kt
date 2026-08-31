package com.example.chameleon

import android.app.Application
import com.example.chameleon.ble.BleCenter

class ChameleonApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        BleCenter.initialize(this)
    }
}
