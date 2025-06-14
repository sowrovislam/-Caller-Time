package com.example.thecalltime

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.thecalltime.databinding.ActivityMainBinding
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPref: SharedPreferences
    private val scheduledCalls = mutableListOf<PendingIntent>()
    private val PREFS_NAME = "CallSchedulerPrefs"
    private val KEY_PHONE_NUMBER = "phone_number"
    private val KEY_SIM_SLOT = "sim_slot"
    private val KEY_HOUR = "hour"
    private val KEY_MINUTE = "minute"
    private val KEY_NUM_CALLS = "num_calls"
    private val KEY_IS_SCHEDULED = "is_scheduled"
    private var currentRequestCode = 1000 // Base request code for pending intents

    // Permission launchers
    private val requestContactPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) pickContact() else showToast("Contact permission denied")
    }

    private val requestCallPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) scheduleCalls() else showToast("Call permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
        createNotificationChannel()
        loadSavedData()
    }

    private fun setupUI() {
        // SIM spinner setup
        val simList = listOf("SIM 1", "SIM 2")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, simList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.simSpinner.adapter = adapter

        // Set initial time to current time
        val calendar = Calendar.getInstance()
        binding.timePicker.hour = calendar.get(Calendar.HOUR_OF_DAY)
        binding.timePicker.minute = calendar.get(Calendar.MINUTE)

        // Button listeners
        binding.selectContactButton.setOnClickListener {
            if (hasContactPermission()) pickContact()
            else requestContactPermission.launch(android.Manifest.permission.READ_CONTACTS)
        }

        binding.scheduleButton.setOnClickListener {
            if (hasCallPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = getSystemService(AlarmManager::class.java)
                    if (!alarmManager.canScheduleExactAlarms()) {
                        showToast("Please enable exact alarms")
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        return@setOnClickListener
                    }
                }
                scheduleCalls()
            } else {
                requestCallPermission.launch(android.Manifest.permission.CALL_PHONE)
            }
        }

        binding.stopButton.setOnClickListener {
            cancelAllScheduledCalls()
        }
    }

    private fun scheduleCalls() {
        val phoneNumber = binding.phoneNumberEditText.text.toString().trim()
        if (phoneNumber.isEmpty()) {
            showToast("Please enter phone number")
            return
        }

        val numCalls = try {
            binding.numberOfCallsEditText.text.toString().toInt().coerceIn(1, 100)
        } catch (e: NumberFormatException) {
            1
        }

        // Cancel any existing calls first
        cancelAllScheduledCalls(showToast = false)

        val selectedSim = binding.simSpinner.selectedItemPosition + 1
        val hour = binding.timePicker.hour
        val minute = binding.timePicker.minute

        // Save schedule details
        saveScheduleDetails(phoneNumber, selectedSim, hour, minute, numCalls)

        // Schedule multiple calls with 1-minute interval
        for (i in 0 until numCalls) {
            val callTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute + i)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(this, CallReceiver::class.java).apply {
                putExtra("phoneNumber", phoneNumber)
                putExtra("simSlot", selectedSim)
                action = "MAKE_AUTO_CALL_${System.currentTimeMillis()}_$i" // Unique action per call
            }

            val pendingIntent = PendingIntent.getBroadcast(
                this,
                currentRequestCode + i, // Unique request code
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            scheduledCalls.add(pendingIntent)

            val alarmManager = getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    callTime.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    callTime.timeInMillis,
                    pendingIntent
                )
            }
        }

        // Update request code for next schedule
        currentRequestCode += numCalls + 1

        // Start foreground service
        ContextCompat.startForegroundService(this, Intent(this, CallSchedulerService::class.java))

        updateStatusText(phoneNumber, selectedSim, hour, minute, numCalls)
        showToast("$numCalls calls scheduled successfully")
    }

    private fun cancelAllScheduledCalls(showToast: Boolean = true) {
        val alarmManager = getSystemService(AlarmManager::class.java)

        // Cancel all pending alarms
        scheduledCalls.forEach { pendingIntent ->
            try {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error canceling alarm", e)
            }
        }
        scheduledCalls.clear()

        // Stop foreground service
        try {
            stopService(Intent(this, CallSchedulerService::class.java))
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping service", e)
        }

        // Clear saved preferences
        with(sharedPref.edit()) {
            clear()
            apply()
        }

        // Reset UI
        runOnUiThread {
            binding.phoneNumberEditText.text.clear()
            binding.numberOfCallsEditText.setText("1")
            binding.timePicker.hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            binding.timePicker.minute = Calendar.getInstance().get(Calendar.MINUTE)
            binding.simSpinner.setSelection(0)
            binding.statusTextView.text = "Status: No calls scheduled"
        }

        if (showToast) {
            showToast("All scheduled calls stopped")
        }
    }

    private fun saveScheduleDetails(phoneNumber: String, simSlot: Int, hour: Int, minute: Int, numCalls: Int) {
        with(sharedPref.edit()) {
            putString(KEY_PHONE_NUMBER, phoneNumber)
            putInt(KEY_SIM_SLOT, simSlot)
            putInt(KEY_HOUR, hour)
            putInt(KEY_MINUTE, minute)
            putInt(KEY_NUM_CALLS, numCalls)
            putBoolean(KEY_IS_SCHEDULED, true)
            apply()
        }
    }

    private fun loadSavedData() {
        if (sharedPref.getBoolean(KEY_IS_SCHEDULED, false)) {
            val phoneNumber = sharedPref.getString(KEY_PHONE_NUMBER, "") ?: ""
            val simSlot = sharedPref.getInt(KEY_SIM_SLOT, 1)
            val hour = sharedPref.getInt(KEY_HOUR, 0)
            val minute = sharedPref.getInt(KEY_MINUTE, 0)
            val numCalls = sharedPref.getInt(KEY_NUM_CALLS, 1)

            binding.phoneNumberEditText.setText(phoneNumber)
            binding.numberOfCallsEditText.setText(numCalls.toString())
            binding.simSpinner.setSelection(simSlot - 1)
            binding.timePicker.hour = hour
            binding.timePicker.minute = minute

            updateStatusText(phoneNumber, simSlot, hour, minute, numCalls)
        } else {
            binding.statusTextView.text = "Status: No calls scheduled"
        }
    }

    private fun updateStatusText(phoneNumber: String, simSlot: Int, hour: Int, minute: Int, numCalls: Int) {
        binding.statusTextView.text = "$numCalls calls scheduled to $phoneNumber starting at ${
            String.format("%02d:%02d", hour, minute)
        } on SIM $simSlot (1 min interval)"
    }

    private fun pickContact() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                CONTACT_PICK_REQUEST
            )
        } catch (e: Exception) {
            showToast("No contacts app found")
            Log.e("MainActivity", "Contact picker error", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONTACT_PICK_REQUEST && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
                    if (it.moveToFirst()) binding.phoneNumberEditText.setText(it.getString(0))
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "call_scheduler_channel",
                "Call Scheduler",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for call scheduling"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun hasContactPermission() = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasCallPermission() = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CONTACT_PICK_REQUEST = 1001
    }
}