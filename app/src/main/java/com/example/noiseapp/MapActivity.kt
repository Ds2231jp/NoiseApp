package com.example.noiseapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.SearchView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.android.libraries.places.api.net.PlacesClient
import java.io.IOException

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient
    private lateinit var searchView: SearchView
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private var locationPermissionGranted = false
    private val TAG = "MapActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        searchView = findViewById(R.id.map_search_view)

        // This line initializes the Places API service. It must be called before creating a client.
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key))
        }
        placesClient = Places.createClient(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupSearchView()
    }

    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrBlank()) {
                    searchPlaces(query)
                }
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })
    }

    private fun searchPlaces(query: String) {
        mMap.clear()

        val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG)

        val searchRequest = SearchByTextRequest.builder(query, placeFields)
            .build()

        placesClient.searchByText(searchRequest)
            .addOnSuccessListener { response ->
                if (response.places.isEmpty()) {
                    Toast.makeText(this, "No results found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val firstPlace = response.places.first()
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(firstPlace.latLng!!, 14f))

                for (place in response.places) {
                    mMap.addMarker(
                        MarkerOptions()
                            .position(place.latLng!!)
                            .title(place.name)
                    )
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Place search failed: ", exception)
                Toast.makeText(this, "Error finding places. Check API key and permissions.", Toast.LENGTH_LONG).show()
            }
    }


    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        getLocationPermission()
    }

    private fun getLocationPermission() {
        if (ContextCompat.checkSelfPermission(this.applicationContext,
                Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            locationPermissionGranted = true
            updateLocationUI()
            getDeviceLocation()
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationPermissionGranted = false
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    locationPermissionGranted = true
                }
            }
        }
        updateLocationUI()
        getDeviceLocation()
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationUI() {
        try {
            if (locationPermissionGranted) {
                mMap.isMyLocationEnabled = true
                mMap.uiSettings.isMyLocationButtonEnabled = true
            } else {
                mMap.isMyLocationEnabled = false
                mMap.uiSettings.isMyLocationButtonEnabled = false
            }
        } catch (e: SecurityException) {
            Log.e("Exception: %s", e.message, e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceLocation() {
        if (locationPermissionGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
                if (location != null) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 14f))
                } else {
                    Toast.makeText(this, "Unable to get current location. Make sure location is enabled on the device.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
