package com.example.thecalltime

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action?.startsWith("MAKE_AUTO_CALL") != true) return

        // Add delay to avoid SIM toolkit interference
        Handler(Looper.getMainLooper()).postDelayed({
            makeTheCall(context, intent)
        }, 2000) // 2 second delay
    }

    private fun makeTheCall(context: Context, intent: Intent) {
        val phoneNumber = intent.getStringExtra("phoneNumber") ?: return
        val simSlot = intent.getIntExtra("simSlot", 1)

        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                // Disable SIM toolkit temporarily
                disableSimToolkit(context)

                val telecomManager = context.getSystemService(TelecomManager::class.java)
                val uri = Uri.parse("tel:$phoneNumber")
                val extras = Bundle().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        putInt("android.telecom.extra.PHONE_ACCOUNT_HANDLE_ID", simSlot)
                        // Add flag to bypass carrier services
                        putBoolean("android.telecom.extra.BYPASS_CARRIER_SERVICES", true)
                    }
                }
                telecomManager.placeCall(uri, extras)
            } catch (e: Exception) {
                Toast.makeText(context, "Call failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                enableSimToolkit(context)
            }
        }
    }

    private fun disableSimToolkit(context: Context) {
        try {
            val pm = context.packageManager
            val component = ComponentName("com.android.stk", "com.android.stk.StkLauncherActivity")
            pm.setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            Log.e("CallReceiver", "Couldn't disable SIM toolkit", e)
        }
    }

    private fun enableSimToolkit(context: Context) {
        try {
            val pm = context.packageManager
            val component = ComponentName("com.android.stk", "com.android.stk.StkLauncherActivity")
            pm.setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            Log.e("CallReceiver", "Couldn't enable SIM toolkit", e)
        }
    }
}