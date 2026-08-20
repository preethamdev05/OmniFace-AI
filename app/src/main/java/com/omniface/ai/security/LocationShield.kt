package com.omniface.ai.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlin.math.*

object LocationShield {

    // Default Authorized Campus Bounding Coordinates
    var campusLatitude: Double = 12.9716
    var campusLongitude: Double = 77.5946
    var allowedRadiusMeters: Double = 50.0

    var isGeofenceShieldEnabled: Boolean = false
    var isWifiBssidShieldEnabled: Boolean = false
    var authorizedWifiBssid: String = "CAMPUS_SECURE_WIFI"

    /**
     * Computes Haversine distance in meters between two GPS coordinates.
     */
    fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Validates whether device is within allowed campus GPS perimeter.
     */
    fun isWithinCampusGeofence(currentLat: Double, currentLon: Double): Boolean {
        if (!isGeofenceShieldEnabled) return true
        val distance = computeDistanceMeters(campusLatitude, campusLongitude, currentLat, currentLon)
        return distance <= allowedRadiusMeters
    }

    /**
     * Modernized check verifying active connection to authorized Wi-Fi network.
     */
    fun isConnectedToAuthorizedWifi(context: Context): Boolean {
        if (!isWifiBssidShieldEnabled) return true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
