package com.example.buber.Views.Activities;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.buber.App;
import com.example.buber.Controllers.ApplicationController;
import com.example.buber.Model.ApplicationModel;
import com.example.buber.Model.Trip;
import com.example.buber.Model.User;
import com.example.buber.Model.UserLocation;
import com.example.buber.R;
import com.example.buber.Views.Components.BUberMapUIAddOnsManager;
import com.example.buber.Views.Components.BUberNotificationManager;
import com.example.buber.Views.UIErrorHandler;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.Task;

import java.util.Observable;
import java.util.Observer;

import static com.example.buber.Model.User.TYPE.DRIVER;
import static com.example.buber.Model.User.TYPE.RIDER;

/**
 * Main Map activity. Activity uses similiar UI for Rider and Driver, but changes functionality based
 * on the type of user currently logged in (Rider, Driver). Activity links into the Google Maps
 * APi which is used to handle the current user location. Future iterations will include using
 * The sessionTrip object to display the route on the map and enable selecting start / end locations
 * TODO: MVC Updating and Error Handling.
 */
public class MapActivity extends AppCompatActivity implements Observer, OnMapReadyCallback, UIErrorHandler {

    // GOOGLE MAP STATE
    public Location mLastKnownUserLocation;
    private static final int ERROR_DIALOG_REQUEST = 9001;
    private GoogleMap mMap;
    private boolean mLocationPermissionGranted = false;
    private static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1234;
    private static final float DEFAULT_ZOOM = 15;
    private LocationListener locationListener;
    private LocationManager locationManager;
    private final long MIN_MAP_UPDATE_INTERVAL = 1000; // update map on a 1 second delta
    private final long MIN_MAP_UPDATE_DISTANCE = 5; // update map on a 5 meters delta (battery life conservation)

    // TRIP STATE
    public Trip.STATUS currentTripStatus = null;

    // OTHER UI ELEMENTS (BUTTONS, SIDE-PANEL, and STATUS)
    private BUberMapUIAddOnsManager uiAddOnsManager;

    // NOTIFICATIONS
    private BUberNotificationManager notifManager;

    /**
     * onCreate method creates MapActivity when it is called
     *
     * @param savedInstanceState is a previous saved state if applicable
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        if (googleConnectionSuccessful()) {
            getLocationPermission();
        } else {
            Toast.makeText(this,
                    "map connection failed", Toast.LENGTH_SHORT).show();
        }

        // INSTANTIATE UI ADD-ONs MANAGER
        uiAddOnsManager = new BUberMapUIAddOnsManager(this);

        // INSTANTIATE NOTIFICATION MANAGER
        notifManager = new BUberNotificationManager(this, MapActivity.class);

        App.getModel().addObserver(this);
        Trip sessionTrip = App.getModel().getSessionTrip();
        if (sessionTrip != null) {
            currentTripStatus = sessionTrip.getStatus();
            uiAddOnsManager.showStatusButton();
            uiAddOnsManager.showActiveMainActionButton();
        }
    }

    /***** OBSERVING OBSERVABLES ******/
    /**
     * update method updates the activity when necessary, USED MOSTLY FOR NOTIFICATIONS
     *
     * @param o,arg are instances of the observable and object
     */
    @Override
    public void update(Observable o, Object arg) {
        Trip sessionTrip = App.getModel().getSessionTrip();
        User.TYPE currentUserType = App.getModel().getSessionUser().getType();

        // Handles State Resets regarding Trip Cancellations
        if (sessionTrip == null) {
            if (this.currentTripStatus != null) {
                switch (this.currentTripStatus) {
                    case DRIVER_ACCEPT:
                        if (currentUserType == DRIVER) {
                            notifManager.notifyOnDriverChannel(
                                    1, "Unfortunately, the rider has declined your offer.",
                                    "", Color.RED);
                        }
                        break;
                    case DRIVER_PICKING_UP:
                        if (currentUserType == DRIVER) {
                            notifManager.notifyOnDriverChannel(
                                    2, "Unfortunately, the rider no longer needs a ride from you.",
                                    "Rider no longer needs to be picked up.", Color.RED);
                        }
                        break;
                }
                this.currentTripStatus = null;
            }
            uiAddOnsManager.showActiveMainActionButton();
        }
        // Handles Forward State Transitions regarding Trip Progress
        else if (sessionTrip.getStatus() != currentTripStatus) {
            this.currentTripStatus = sessionTrip.getStatus();
            switch (sessionTrip.getStatus()) {
                case DRIVER_ACCEPT:
                    if (currentUserType == RIDER) {
                        notifManager.notifyOnRiderChannel(
                                3, "A driver has accepted your request!",
                                "BUber requires your action!",
                                Color.GREEN);
                    }
                    break;
                case DRIVER_PICKING_UP:
                    if (currentUserType == RIDER) {
                        notifManager.notifyOnRiderChannel(
                                4, "Your ride is on the way!",
                                "",
                                Color.GREEN
                        );
                    } else if (currentUserType == DRIVER) {
                        notifManager.notifyOnDriverChannel(
                                5, "The rider has accepted and is now ready for pickup!",
                                "Rider Username: " + sessionTrip.getRiderUserName(),
                                Color.GREEN);
                    }
                    break;
            }
            uiAddOnsManager.showActiveMainActionButton();
        }
        // Unknown states
        else {
            Log.e("%s", "Unknown state has been encountered!");
        }
    }


    /**
     * handleScreenClick handles what happens when user clicks on the screen
     *
     * @param v is the view instance
     */
    public void handleScreenClick(View v) {
        if (uiAddOnsManager.isShowSideBar()) {
            uiAddOnsManager.hideSettingsPanel();
            uiAddOnsManager.showActiveMainActionButton();
        } else {
            // TODO: Handle Everything else
        }
    }


    /**
     * onMapReady lets the user know that the location is being gotten if permissions are granted
     *
     * @param googleMap is the map instance of GoogleMap
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        if (mLocationPermissionGranted) {
            Log.d("GETTING LOCATION", "Trying to get current location");
            getDeviceLocation();
            mMap.setMyLocationEnabled(true);
        }

        mMap.setOnMapClickListener(latLng -> {
            if (uiAddOnsManager.isShowSideBar()) {
                uiAddOnsManager.hideSettingsPanel();
                uiAddOnsManager.showActiveMainActionButton();
            }
        });

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {

            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {

            }

            @Override
            public void onProviderEnabled(String provider) {

            }

            @Override
            public void onProviderDisabled(String provider) {

            }
        };

        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_MAP_UPDATE_INTERVAL,
                    MIN_MAP_UPDATE_DISTANCE,
                    locationListener);
        } catch (SecurityException sece) {
            sece.printStackTrace();
        }

    }

    /***** BUILDING CUSTOM GOOGLE MAP (CODE REFERENCED FROM GOOGLE API DOCS) ******/
    public void initializeMap() {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(MapActivity.this);  //calls onMapReady
    }

    /**
     * getLocationPermission gets location permission from the user
     */
    private void getLocationPermission() {
        /*
         * Request location permission, so that we can get the location of the
         * device. The result of the permission request is handled by a callback,
         * onRequestPermissionsResult.
         * Code reference from Google API docs
         */
        if (ContextCompat.checkSelfPermission(this.getApplicationContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mLocationPermissionGranted = true;
            initializeMap();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }
    }

    /**
     * Gets the location of the users device
     */
    private void getDeviceLocation() {
        /*
         * Get the best and most recent location of the device, which may be null in rare
         * cases when a location is not available.
         */
        FusedLocationProviderClient mFusedLocationProviderClient;
        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        try {
            if (mLocationPermissionGranted) {
                Task locationResult = mFusedLocationProviderClient.getLastLocation();
                locationResult.addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Set the map's camera position to the current location of the device.
                        mLastKnownUserLocation = (Location) task.getResult();
                        //TODO::fix controller lat and long

                        App.getController()
                                .updateUserLocation(
                                        new UserLocation(
                                                mLastKnownUserLocation.getLatitude(),
                                                mLastKnownUserLocation.getLongitude()
                                        )
                                );


                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                new LatLng(mLastKnownUserLocation.getLatitude(),
                                        mLastKnownUserLocation.getLongitude()), DEFAULT_ZOOM));

                    } else {
                        Log.d("NULLLOCATION", "Current location is null. Using defaults.");
                        Log.e("LOCATIONEXCEPTION", "Exception: %s", task.getException());
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                new LatLng(mLastKnownUserLocation.getLatitude(),
                                        mLastKnownUserLocation.getLongitude()), DEFAULT_ZOOM));
                        mMap.getUiSettings().setMyLocationButtonEnabled(false);
                    }
                });
            }
        } catch (SecurityException e) {
            Log.e("Exception: %s", e.getMessage());
        }
    }

    /**
     * Returns true if connection to google api is successful
     */
    boolean googleConnectionSuccessful() {
        int connection = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(MapActivity.this);
        if (connection == ConnectionResult.SUCCESS) {
            Log.d("MAPCONNECTIONSUCCESS", "Map connection Successful");
            return true;
        } else if (GoogleApiAvailability.getInstance().isUserResolvableError(connection)) {
            //for debugging. if an error occurs, print the error
            Log.e("MAPERROR", "an error occurred loading map");
            Dialog dialog = GoogleApiAvailability.getInstance().getErrorDialog(MapActivity.this, connection, ERROR_DIALOG_REQUEST);
            dialog.show();
        } else {
            Log.e("MAPCONNECTIONFAILURE", "Map failed to connect");
        }
        return false;
    }

    /**
     * onRequestPermissionsResult gives the result of of the user permissions request
     *
     * @param requestCode  is the result of the request
     * @param permissions  is a list of the permissions
     * @param grantResults is a list of results granted
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        //this code is copied from google map api documentation
        mLocationPermissionGranted = false;
        switch (requestCode) {
            case PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mLocationPermissionGranted = true;
                    initializeMap();
                    Log.d("LOCATIONPERMISSION", "UserLocation permission granted");
                } else {
                    Log.d("LOCATIONPERMISSION", "UserLocation permission denied");
                }
            }
        }
    }


    /***** DECLARING REST OF LIFECYCLE METHODS ******/
    /**
     * onDestroy method destructs activity if it is closed down
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        // THIS CODE SHOULD BE IMPLEMENTED IN EVERY VIEW
        ApplicationModel m = App.getModel();
        m.deleteObserver(this);
    }

    /**
     * onError handles UI Errors if there are any
     */
    @Override
    public void onError(Error e) {
        // TODO: Handle UI Errors
    }
}
