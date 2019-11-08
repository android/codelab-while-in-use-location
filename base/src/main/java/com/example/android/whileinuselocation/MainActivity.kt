/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.whileinuselocation

import android.Manifest
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.location.Location
import android.net.Uri
import android.os.IBinder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.os.BuildCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

import com.google.android.material.snackbar.Snackbar
/**
 *  This app allows a user to track their location.
 *
 *  One or the features in the app creates a foreground service (tied to a Notification) when the
 *  user navigates away from the app. Because of this, it only needs foreground or "while in use"
 *  location permissions. That is, there is no need to ask for location in the background (which
 *  requires additional permissions in the manifest).
 *
 *  There is another feature that does require location in both the foreground and background to
 *  show the proper way to get location for that scenario.
 *
 *  Note: Users have three options in Android 10+ regarding location:
 *
 *  * Allow all the time
 *  * Allow while app is in use, i.e., while app is in foreground
 *  * Not allow location at all
 *
 * It is generally recommended you only request "while in use" location permissions (location only
 * needed in the foreground). If your app does have a feature that requires background, request
 * that permission in context and handle it gracefully if the user denies the request or only
 * allows "while-in-use".
 *
 * Android 10 also now requires developers to specify foreground service type in the manifest (in
 * this case, "location").
 *
 * For the feature that requires location in the foreground, this sample uses a long-running bound
 * and started service for location updates. The service is aware of foreground status of this
 * activity, which is the only bound client in this sample.
 *
 * While getting location in the foreground, if the activity ceases to be in the foreground (user
 * navigates away from the app), the service promotes itself to a foreground service and continues
 * receiving location updates.
 *
 * When the activity comes back to the foreground, the foreground service stops, and the
 * notification associated with that foreground service is removed.
 *
 * While the foreground service notification is displayed, the user has the option to launch the
 * activity from the notification. The user can also remove location updates directly from the
 * notification. This dismisses the notification and stops the service.
 */
class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var foregroundOnlyLocationServiceBound = false
    private var foregroundAndBackgroundLocationEnabled = false

    // TODO: Step 3.2, add check for devices with Android 10.
    private val runningQOrLater = false

    // Provides location updates for while-in-use feature.
    private var foregroundOnlyLocationService: ForegroundOnlyLocationService? = null

    // Listens for location broadcasts from ForegroundOnlyLocationService.
    private lateinit var foregroundOnlyBroadcastReceiver: ForegroundOnlyBroadcastReceiver

    private lateinit var sharedPreferences:SharedPreferences

    private lateinit var foregroundOnlyLocationButton: Button
    private lateinit var foregroundAndBackgroundLocationButton: Button

    private lateinit var outputTextView: TextView

    // Monitors connection to the while-in-use service.
    private val foregroundOnlyServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ForegroundOnlyLocationService.LocalBinder
            foregroundOnlyLocationService = binder.service
            foregroundOnlyLocationServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            foregroundOnlyLocationService = null
            foregroundOnlyLocationServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        foregroundOnlyBroadcastReceiver = ForegroundOnlyBroadcastReceiver()

        sharedPreferences =
            getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)

        foregroundOnlyLocationButton = findViewById(R.id.foreground_only_location_button)
        foregroundAndBackgroundLocationButton =
            findViewById(R.id.foreground_and_background_location_button)
        outputTextView = findViewById(R.id.output_text_view)

        foregroundOnlyLocationButton.setOnClickListener {

            val enabled = sharedPreferences.getBoolean(
                SharedPreferenceUtil.KEY_FOREGROUND_ONLY_ENABLED, false)

            if (enabled) {
                foregroundOnlyLocationService?.stopTrackingLocation()
            } else {
                if (foregroundPermissionApproved()) {
                    foregroundOnlyLocationService?.startTrackingLocation()
                        ?: Log.d(TAG, "Service Not Bound")
                } else {
                    requestForegroundPermissions()
                }
            }
        }

        foregroundAndBackgroundLocationButton.setOnClickListener {
            when {
                foregroundAndBackgroundLocationEnabled -> stopForegroundAndBackgroundLocation()
                else -> {
                    if (foregroundAndBackgroundPermissionApproved()) {
                        startForegroundAndBackgroundLocation()
                    } else {
                        requestForegroundAndBackgroundPermissions()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        updateForegroundOnlyButtonsState(
            sharedPreferences.getBoolean(SharedPreferenceUtil.KEY_FOREGROUND_ONLY_ENABLED, false)
        )
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        val serviceIntent = Intent(this, ForegroundOnlyLocationService::class.java)
        bindService(serviceIntent, foregroundOnlyServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            foregroundOnlyBroadcastReceiver,
            IntentFilter(
                ForegroundOnlyLocationService.ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST)
        )
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(
            foregroundOnlyBroadcastReceiver
        )
        super.onPause()
    }

    override fun onStop() {
        if (foregroundOnlyLocationServiceBound) {
            unbindService(foregroundOnlyServiceConnection)
            foregroundOnlyLocationServiceBound = false
        }
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        // Updates button states if new while in use location is added to SharedPreferences.
        if (key == SharedPreferenceUtil.KEY_FOREGROUND_ONLY_ENABLED) {
            updateForegroundOnlyButtonsState(sharedPreferences.getBoolean(
                SharedPreferenceUtil.KEY_FOREGROUND_ONLY_ENABLED, false)
            )
        }
    }

    private fun foregroundPermissionApproved(): Boolean {
        return PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private fun foregroundAndBackgroundPermissionApproved(): Boolean {
        val foregroundLocationApproved = foregroundPermissionApproved()

        // TODO: Step 3.3, Add check for background permission.
        val backgroundPermissionApproved = true

        return foregroundLocationApproved && backgroundPermissionApproved
    }

    private fun requestForegroundPermissions() {

        val provideRationale = foregroundPermissionApproved()

        // If the user denied a previous request, but didn't check "Don't ask again", provide
        // additional rationale.
        if (provideRationale) {
            Snackbar.make(
                findViewById(R.id.activity_main),
                R.string.permission_rationale,
                Snackbar.LENGTH_LONG
            )
                .setAction(R.string.ok) {
                    // Request permission
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE
                    )
                }
                .show()
        } else {
            Log.d(TAG, "Request foreground only permission")
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private fun requestForegroundAndBackgroundPermissions() {
        val provideRationale = foregroundAndBackgroundPermissionApproved()

        val permissionRequests = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        // TODO: Step 3.4, Add another entry to permission request array.


        // If the user denied a previous request, but didn't check "Don't ask again", provide
        // additional rationale.
        if (provideRationale) {
            Snackbar.make(
                findViewById(R.id.activity_main),
                R.string.permission_rationale,
                Snackbar.LENGTH_LONG
            )
                .setAction(R.string.ok) {
                    // Request permission(s)
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        permissionRequests.toTypedArray(),
                        REQUEST_FOREGROUND_AND_BACKGROUND_REQUEST_CODE
                    )
                }
                .show()
        } else {
            Log.d(TAG, "Request foreground and background permission")
            ActivityCompat.requestPermissions(
                this@MainActivity,
                permissionRequests.toTypedArray(),
                REQUEST_FOREGROUND_AND_BACKGROUND_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        Log.d(TAG, "onRequestPermissionResult")

        when (requestCode) {
            REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE -> when {
                grantResults.isEmpty() ->
                    // If user interaction was interrupted, the permission request
                    // is cancelled and you receive empty arrays.
                    Log.d(TAG, "User interaction was cancelled.")

                grantResults[0] == PackageManager.PERMISSION_GRANTED ->
                    // Permission was granted.
                    foregroundOnlyLocationService?.startTrackingLocation()

                else -> {
                    // Permission denied.
                    updateForegroundOnlyButtonsState(false)

                    Snackbar.make(
                        findViewById(R.id.activity_main),
                        R.string.permission_denied_explanation,
                        Snackbar.LENGTH_LONG
                    )
                        .setAction(R.string.settings) {
                            // Build intent that displays the App settings screen.
                            val intent = Intent()
                            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            val uri = Uri.fromParts(
                                "package",
                                BuildConfig.APPLICATION_ID,
                                null
                            )
                            intent.data = uri
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(intent)
                        }
                        .show()
                }
            }

            REQUEST_FOREGROUND_AND_BACKGROUND_REQUEST_CODE -> {

                var foregroundAndBackgroundLocationApproved =
                    grantResults[0] == PackageManager.PERMISSION_GRANTED

                // TODO: Step 3.5, For Android 10, check if background permissions approved in request code.


                // If user interaction was interrupted, the permission request
                // is cancelled and you receive empty arrays.
                when {
                    grantResults.isEmpty() -> Log.d(TAG, "User interaction was cancelled.")
                    // TODO: Step 3.6, review method call for foreground and background location.
                    foregroundAndBackgroundLocationApproved ->
                        startForegroundAndBackgroundLocation()
                    else -> {
                        // Permission denied.
                        updateForegroundOnlyButtonsState(false)

                        Snackbar.make(
                            findViewById(R.id.activity_main),
                            R.string.permission_denied_explanation,
                            Snackbar.LENGTH_LONG
                        )
                            .setAction(R.string.settings) {
                                // Build intent that displays the App settings screen.
                                val intent = Intent()
                                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                val uri = Uri.fromParts(
                                    "package",
                                    BuildConfig.APPLICATION_ID,
                                    null
                                )
                                intent.data = uri
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                startActivity(intent)
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun updateForegroundOnlyButtonsState(trackingLocation: Boolean) {
        if (trackingLocation) {
            foregroundOnlyLocationButton.text = getString(R.string.disable_foreground_only_location)
        } else {
            foregroundOnlyLocationButton.text = getString(R.string.enable_foreground_only_location)
        }
    }

    private fun updateForegroundAndBackgroundButtonsState() {
        if (foregroundAndBackgroundLocationEnabled) {
            foregroundAndBackgroundLocationButton.text =
                getString(R.string.disable_foreground_and_background_location)
        } else {
            foregroundAndBackgroundLocationButton.text =
                getString(R.string.enable_foreground_and_background_location)
        }
    }

    private fun startForegroundAndBackgroundLocation() {
        Log.d(TAG, "startForegroundAndBackgroundLocation()")
        foregroundAndBackgroundLocationEnabled = true
        updateForegroundAndBackgroundButtonsState()
        logResultsToScreen("Foreground and background location enabled.")
        // TODO: Add your specific background tracking logic here (start tracking).
    }

    private fun stopForegroundAndBackgroundLocation() {
        Log.d(TAG, "stopForegroundAndBackgroundLocation()")
        foregroundAndBackgroundLocationEnabled = false
        updateForegroundAndBackgroundButtonsState()
        logResultsToScreen("Foreground and background location disabled.")
        // TODO: Add your specific background tracking logic here (stop tracking).
    }

    private fun logResultsToScreen(output:String) {
        val outputWithPreviousLogs = "$output\n${outputTextView.text}"
        outputTextView.text = outputWithPreviousLogs
    }

    /**
     * Receiver for location broadcasts from [ForegroundOnlyLocationService].
     */
    private inner class ForegroundOnlyBroadcastReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val location = intent.getParcelableExtra<Location>(
                ForegroundOnlyLocationService.EXTRA_LOCATION
            )

            if (location != null) {
                logResultsToScreen("Foreground only location: ${location.toText()}")
            }
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val REQUEST_FOREGROUND_AND_BACKGROUND_REQUEST_CODE = 56
        private const val REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE = 34
    }
}
