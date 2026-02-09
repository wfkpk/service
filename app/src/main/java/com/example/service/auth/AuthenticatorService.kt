package com.example.service.auth

import android.app.Service
import android.content.Intent
import android.os.IBinder

class AuthenticatorService : Service() {

    private lateinit var authenticator: SsoAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = SsoAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return authenticator.iBinder
    }
}
