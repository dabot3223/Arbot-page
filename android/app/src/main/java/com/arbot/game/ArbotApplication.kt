package com.arbot.game

import android.app.Application

class ArbotApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.integrity.warmUp(container.appScope)
    }
}
