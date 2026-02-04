package com.example.service

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.ssoapi.sso

/**
 * Main activity showing SSO Service status.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvActiveAccount: TextView
    private lateinit var tvTotalAccounts: TextView
    private lateinit var btnRefresh: Button
    private lateinit var statusIndicator: View

    private var ssoService: sso? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            ssoService = sso.Stub.asInterface(service)
            isBound = true
            updateStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ssoService = null
            isBound = false
            updateServiceStatusUI(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvActiveAccount = findViewById(R.id.tvActiveAccount)
        tvTotalAccounts = findViewById(R.id.tvTotalAccounts)
        btnRefresh = findViewById(R.id.btnRefresh)
        statusIndicator = findViewById(R.id.statusIndicator)

        btnRefresh.setOnClickListener {
            refreshStatus()
        }

        // Start service if not running
        startServiceIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        bindToService()
    }

    override fun onStop() {
        super.onStop()
        unbindFromService()
    }

    private fun startServiceIfNeeded() {
        if (!isServiceRunning()) {
            val serviceIntent = Intent(this, SsoService::class.java)
            startForegroundService(serviceIntent)
            Toast.makeText(this, "SSO Service started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "SSO Service already running", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (SsoService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun bindToService() {
        val intent = Intent(this, SsoService::class.java)
        intent.action = SsoService.ACTION_BIND
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun refreshStatus() {
        if (isBound) {
            updateStatus()
        } else {
            bindToService()
        }
    }

    private fun updateStatus() {
        val running = isServiceRunning()
        updateServiceStatusUI(running)

        if (isBound && ssoService != null) {
            try {
                // Get active account
                val activeAccount = ssoService?.activeAccount
                if (activeAccount != null) {
                    tvActiveAccount.text = activeAccount.email
                } else {
                    tvActiveAccount.text = "None"
                }

                // Get total accounts
                val allAccounts = ssoService?.allAccounts
                tvTotalAccounts.text = (allAccounts?.size ?: 0).toString()

            } catch (e: Exception) {
                tvActiveAccount.text = "Error"
                tvTotalAccounts.text = "Error"
            }
        }
    }

    private fun updateServiceStatusUI(running: Boolean) {
        if (running) {
            tvServiceStatus.text = "Running"
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
            statusIndicator.setBackgroundColor(Color.parseColor("#4CAF50"))
        } else {
            tvServiceStatus.text = "Stopped"
            tvServiceStatus.setTextColor(Color.parseColor("#F44336")) // Red
            statusIndicator.setBackgroundColor(Color.parseColor("#F44336"))
        }
    }
}
