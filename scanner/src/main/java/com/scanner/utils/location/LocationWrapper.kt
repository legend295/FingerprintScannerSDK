package com.scanner.utils.location

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.location.Location
import android.location.LocationManager
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
    private lateinit var mFusedLocationClient: FusedLocationProviderClient
    @SuppressLint("MissingPermission")
    fun getLocation(isSuccess: (Boolean) -> Unit) {
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)
        getLastLocation(isSuccess)
        val task = mFusedLocationClient.lastLocation
        task.addOnSuccessListener { location ->
            location ?: return@addOnSuccessListener
            val latLng = LatLng(location.latitude, location.longitude)
            ScannerActivity.location = latLng
            isSuccess(true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocation(isSuccess: (Boolean) -> Unit) {
        if (activity.isLocationEnabled()) {
            mFusedLocationClient =
                LocationServices.getFusedLocationProviderClient(activity)
            val cts = CancellationTokenSource()
            mFusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cts.token
            ).addOnSuccessListener { location ->
                location?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    ScannerActivity.location = latLng
                }
            }
            mFusedLocationClient.lastLocation.addOnCompleteListener(activity) { task ->
                val location: Location? = task.result
                if (location == null) {
                    requestNewLocationData(isSuccess)
                } else {
                    val latLng = LatLng(location.latitude, location.longitude)
                    ScannerActivity.location = latLng
                    isSuccess(true)
                }
            }
        } else {
            isSuccess(false)
//                showToast(getString(R.string.enable_gps_msg))
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData(isSuccess: (Boolean) -> Unit) {
        val locationRequest =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L)
                .setMinUpdateDistanceMeters(2f)
        mFusedLocationClient =
            LocationServices.getFusedLocationProviderClient(activity)
        mFusedLocationClient.requestLocationUpdates(
            locationRequest.build(),
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(p0: LocationResult) {
            if (p0.locations.isNotEmpty()) {
                val latLng =
                    LatLng(p0.locations[0].latitude, p0.locations[0].longitude)
                ScannerActivity.location = latLng
            }
        }
    }

    private fun Activity.isLocationEnabled(): Boolean {
        val locationManager: LocationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }
}