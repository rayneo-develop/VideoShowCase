package com.example.video.show.glass

import com.ffalcon.mercury.android.sdk.MercurySDK
import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
    }
}