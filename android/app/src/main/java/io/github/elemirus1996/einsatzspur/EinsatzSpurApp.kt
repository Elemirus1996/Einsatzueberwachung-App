package io.github.elemirus1996.einsatzspur

import android.app.Application

class EinsatzSpurApp : Application() {
    lateinit var modell: AppModell
        private set

    override fun onCreate() {
        super.onCreate()
        modell = AppModell(this)
    }
}
