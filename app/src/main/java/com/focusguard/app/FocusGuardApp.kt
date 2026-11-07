package com.focusguard.app

import android.app.Application

class FocusGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}
