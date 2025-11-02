package com.example.sobti

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.sobti.aws.AWSConfig
import com.example.sobti.aws.DynamoDBManager
import com.example.sobti.aws.DynamoDBManager.GetUserCallback
import com.example.sobti.aws.DynamoDBManager.UpdateCallback
import com.example.sobti.health.aws.DynamoDBManager.UserData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.wearable.DataClient.OnDataChangedListener
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

class MainActivity : AppCompatActivity(), OnDataChangedListener {
    private var tvHeartRate: TextView? = null
    private var tvSteps: TextView? = null
    private var tvLocation: TextView? = null
    private var tvUserName: TextView? = null
    private var tvStatus: TextView? = null
    private var dbManager: DynamoDBManager? = null
    private var prefs: SharedPreferences? = null
    private var userEmail: String? = null
    private var emergencyNumber: String? = null

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var currentLocation: String? = "Loading..."

    private var currentHeartRate = 0
    private var previousHeartRate = 0
    private var heartRateTrendCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("SobtiPrefs", MODE_PRIVATE)
        userEmail = prefs?.getString("user_email", "")

        // Initialize AWS
        AWSConfig.initialize(this)
        dbManager = DynamoDBManager(AWSConfig.getDDBClient())

        initViews()
        requestPermissions()
        setupLocationTracking()
        loadUserData()
        connectToWearable()
    }

    private fun initViews() {
        tvUserName = findViewById(R.id.tvUserName)
        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvSteps = findViewById(R.id.tvSteps)
        tvLocation = findViewById(R.id.tvLocation)
        tvStatus = findViewById(R.id.tvStatus)
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )

        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    private fun setupLocationTracking() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val locationRequest = LocationRequest.create()
                .setInterval(30000) // 30 seconds
                .setFastestInterval(15000)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)

            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val location = locationResult.lastLocation
                        if (location != null) {
                            currentLocation = String.format(
                                "%.6f, %.6f",
                                location.latitude, location.longitude
                            )
                            updateLocationUI()
                        }
                    }
                },
                null
            )
        }
    }

    private fun loadUserData() {
        dbManager?.getUserData(userEmail, object : GetUserCallback {
            override fun onSuccess(userData: UserData) {
                runOnUiThread {
                    tvUserName?.text = "Welcome, ${userData.name}!"
                    emergencyNumber = userData.emergencyNumber

                    // Load last known values
                    if (userData.lastHeartRate > 0) {
                        tvHeartRate?.text = "${userData.lastHeartRate} bpm"
                    }
                    if (userData.lastSteps > 0) {
                        tvSteps?.text = userData.lastSteps.toString()
                    }
                    if (userData.lastLocation != null) {
                        currentLocation = userData.lastLocation
                        updateLocationUI()
                    }
                }
            }

            override fun onError(e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error loading user data: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    private fun connectToWearable() {
        Wearable.getDataClient(this).addListener(this)
        tvStatus?.text = "Status: Connected to Watch"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val item = event.dataItem
                if (item.uri.path == WEAR_DATA_PATH) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap

                    val heartRate = dataMap.getInt("heartRate", 0)
                    val steps = dataMap.getInt("steps", 0)

                    runOnUiThread {
                        updateHealthData(heartRate, steps)
                    }
                }
            }
        }
    }

    private fun updateHealthData(heartRate: Int, steps: Int) {
        // Update UI
        tvHeartRate?.text = "$heartRate bpm"
        tvSteps?.text = steps.toString()

        // Check heart rate trends
        checkHeartRateTrend(heartRate)

        // Update DynamoDB
        dbManager?.updateHealthData(
            userEmail, heartRate, steps, currentLocation,
            object : UpdateCallback {
                override fun onSuccess() {
                    // Data synced successfully
                }

                override fun onError(e: Exception?) {
                    // Handle error silently or log
                }
            })
    }

    private fun checkHeartRateTrend(heartRate: Int) {
        currentHeartRate = heartRate

        // Check if heart rate is continuously increasing
        if (currentHeartRate > previousHeartRate && currentHeartRate > HIGH_HR_THRESHOLD) {
            heartRateTrendCount++
        } else if (currentHeartRate < previousHeartRate && currentHeartRate < LOW_HR_THRESHOLD) {
            heartRateTrendCount++
        } else {
            heartRateTrendCount = 0 // Reset counter
        }

        // Trigger emergency if trend continues
        if (heartRateTrendCount >= TREND_THRESHOLD) {
            triggerEmergency(heartRate)
            heartRateTrendCount = 0 // Reset after triggering
        }

        previousHeartRate = currentHeartRate
    }

    private fun triggerEmergency(heartRate: Int) {
        if (emergencyNumber.isNullOrEmpty()) {
            return
        }

        val message = String.format(
            "SOBTI ALERT!\nAbnormal heart rate detected: %d bpm\nLocation: %s\nImmediate assistance needed!",
            heartRate, currentLocation
        )

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(emergencyNumber, null, message, null, null)

                runOnUiThread {
                    tvStatus?.text = "Status: Emergency SMS Sent!"
                    tvStatus?.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    Toast.makeText(this, "Emergency alert sent!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateLocationUI() {
        tvLocation?.text = currentLocation
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }

            if (!allGranted) {
                Toast.makeText(
                    this,
                    "Some permissions denied. App may not work properly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val WEAR_DATA_PATH = "/health_data"
        private const val TREND_THRESHOLD = 3 // 3 consecutive readings
        private const val HIGH_HR_THRESHOLD = 120
        private const val LOW_HR_THRESHOLD = 50
    }
}
