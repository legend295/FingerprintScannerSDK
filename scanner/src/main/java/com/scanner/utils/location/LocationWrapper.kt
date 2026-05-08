package com.scanner.utils.location

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.model.LatLng
import com.scanner.activity.ScannerActivity

internal class LocationWrapper(private val activity: Activity) {

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var legacyLocationListener: LocationListener? = null
    private var locationManager: LocationManager? = null

    @SuppressLint("MissingPermission")
    fun getLocation(isSuccess: (Boolean) -> Unit) {
        if (!isLocationEnabled(activity)) {
            isSuccess(false)
            return
        }

        if (isGmsAvailable()) {
            getLocationViaFused(isSuccess)
        } else {
            getLocationViaLegacy(isSuccess)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLocationViaFused(isSuccess: (Boolean) -> Unit) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)

        // 1. Try getCurrentLocation first (most accurate, works even without cached fix)
        val cts = CancellationTokenSource()
        fusedLocationClient!!.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    ScannerActivity.location = LatLng(location.latitude, location.longitude)
                    isSuccess(true)
                    return@addOnSuccessListener
                }
                // 2. Fall back to lastLocation
                tryLastLocationThenUpdates(isSuccess)
            }
            .addOnFailureListener {
                // 3. Fall back to lastLocation on failure
                tryLastLocationThenUpdates(isSuccess)
            }
    }

    @SuppressLint("MissingPermission")
    private fun tryLastLocationThenUpdates(isSuccess: (Boolean) -> Unit) {
        fusedLocationClient?.lastLocation?.addOnCompleteListener(activity) { task ->
            val location: Location? = task.result
            if (location != null) {
                ScannerActivity.location = LatLng(location.latitude, location.longitude)
                isSuccess(true)
            } else {
                requestFusedLocationUpdates(isSuccess)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFusedLocationUpdates(isSuccess: (Boolean) -> Unit) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: result.locations.firstOrNull()
                if (loc != null) {
                    ScannerActivity.location = LatLng(loc.latitude, loc.longitude)
                    isSuccess(true)
                } else {
                    isSuccess(false)
                }
                stopFusedUpdates()
            }
        }

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    @SuppressLint("MissingPermission")
    private fun getLocationViaLegacy(isSuccess: (Boolean) -> Unit) {
        locationManager =
            activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val provider = when {
            locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> {
                isSuccess(false)
                return
            }
        }

        // Use cached last known location if fresh enough (< 2 minutes old)
        val lastKnown = locationManager!!.getLastKnownLocation(provider)
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 120_000L) {
            ScannerActivity.location = LatLng(lastKnown.latitude, lastKnown.longitude)
            isSuccess(true)
            return
        }

        legacyLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                ScannerActivity.location = LatLng(location.latitude, location.longitude)
                isSuccess(true)
                stopLegacyUpdates()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        locationManager!!.requestLocationUpdates(
            provider,
            0L,
            0f,
            legacyLocationListener!!,
            Looper.getMainLooper()
        )
    }

    fun stopUpdates() {
        stopFusedUpdates()
        stopLegacyUpdates()
    }

    private fun stopFusedUpdates() {
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun stopLegacyUpdates() {
        legacyLocationListener?.let { locationManager?.removeUpdates(it) }
        legacyLocationListener = null
    }

    fun isLocationEnabled(activity: Activity): Boolean {
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun isGmsAvailable(): Boolean {
        return try {
            val result = com.google.android.gms.common.GoogleApiAvailability
                .getInstance()
                .isGooglePlayServicesAvailable(activity)
            result == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Exception) {
            false
        }
    }
}
