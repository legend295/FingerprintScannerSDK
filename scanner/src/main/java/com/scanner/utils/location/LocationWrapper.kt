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

internal class LocationWrapper(private val activity: Activity) {

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var legacyLocationListener: LocationListener? = null
    private var locationManager: LocationManager? = null

    /**
     * Requests the device's current location and delivers it via [onLocation].
     *
     * The callback receives the [LatLng] on success, or null if the location could not be
     * determined (provider disabled, no cached fix, or a platform error). The caller is
     * responsible for storing the value wherever it belongs — this class does not write to
     * any shared state.
     *
     * @param onLocation  Invoked once with the location (or null) on the main thread.
     */
    @SuppressLint("MissingPermission")
    fun getLocation(onLocation: (LatLng?) -> Unit) {
        if (!isLocationEnabled(activity)) {
            onLocation(null)
            return
        }

        if (isGmsAvailable()) {
            getLocationViaFused(onLocation)
        } else {
            getLocationViaLegacy(onLocation)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLocationViaFused(onLocation: (LatLng?) -> Unit) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)

        val cts = CancellationTokenSource()
        fusedLocationClient!!.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    onLocation(LatLng(location.latitude, location.longitude))
                    return@addOnSuccessListener
                }
                tryLastLocationThenUpdates(onLocation)
            }
            .addOnFailureListener {
                tryLastLocationThenUpdates(onLocation)
            }
    }

    @SuppressLint("MissingPermission")
    private fun tryLastLocationThenUpdates(onLocation: (LatLng?) -> Unit) {
        fusedLocationClient?.lastLocation?.addOnCompleteListener(activity) { task ->
            val location: Location? = task.result
            if (location != null) {
                onLocation(LatLng(location.latitude, location.longitude))
            } else {
                requestFusedLocationUpdates(onLocation)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFusedLocationUpdates(onLocation: (LatLng?) -> Unit) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: result.locations.firstOrNull()
                onLocation(loc?.let { LatLng(it.latitude, it.longitude) })
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
    private fun getLocationViaLegacy(onLocation: (LatLng?) -> Unit) {
        locationManager =
            activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val provider = when {
            locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> {
                onLocation(null)
                return
            }
        }

        val lastKnown = locationManager!!.getLastKnownLocation(provider)
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 120_000L) {
            onLocation(LatLng(lastKnown.latitude, lastKnown.longitude))
            return
        }

        legacyLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(LatLng(location.latitude, location.longitude))
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
