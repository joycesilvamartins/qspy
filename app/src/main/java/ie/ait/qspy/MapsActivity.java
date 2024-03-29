package ie.ait.qspy;

import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.os.Bundle;

import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;

import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.model.PlaceLikelihood;

import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsResponse;
import com.google.android.libraries.places.api.net.FindCurrentPlaceRequest;
import com.google.android.libraries.places.api.net.FindCurrentPlaceResponse;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.Timestamp;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.iid.FirebaseInstanceId;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ie.ait.qspy.entities.QueueSubscriptionEntity;
import ie.ait.qspy.entities.StoreDetails;
import ie.ait.qspy.entities.LevelEntity;
import ie.ait.qspy.entities.QueueRecordEntity;

import ie.ait.qspy.entities.StoreEntity;
import ie.ait.qspy.services.FirestoreService;
import ie.ait.qspy.services.LevelService;
import ie.ait.qspy.services.StoreService;
import ie.ait.qspy.services.UserService;
import ie.ait.qspy.utils.DeviceUtils;

public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback, GoogleMap.OnInfoWindowClickListener {

    private static final String TAG = MapsActivity.class.getSimpleName();

    private final LatLng defaultLocation = new LatLng(-33.8523341, 151.2106085);
    private static final int DEFAULT_ZOOM = 15;
    private static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1;
    //Keys for storing activity state.
    private static final String KEY_CAMERA_POSITION = "camera_position";
    private static final String KEY_LOCATION = "location";
    //Used for selecting the current place.
    private static final int M_MAX_ENTRIES = 30;

    private LevelService levelService = new LevelService();
    private UserService userService = new UserService();
    private StoreService storeService = new StoreService();

    private String deviceId;

    private long currentPoints;
    private LevelEntity levelEntity;

    private CameraPosition cameraPosition;
    //The entry point to the Places API.
    private PlacesClient placesClient;

    private boolean locationPermissionGranted;
    //The geographical location where the device is currently located. That is, the last-known location retrieved by the Fused Location Provider.
    private Location lastLocation;

    private FusedLocationProviderClient fusedLocationProviderClient;

    private GoogleMap map;

    private Marker reportMarker;

    private SearchView searchView;

    //Access a Cloud Firestore instance from MapActivity.
    private FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Retrieve location and camera position from saved instance state.
        if (savedInstanceState != null) {
            lastLocation = savedInstanceState.getParcelable(KEY_LOCATION);
            cameraPosition = savedInstanceState.getParcelable(KEY_CAMERA_POSITION);
        }
        //Retrieve the content view that renders the map.
        setContentView(R.layout.activity_maps);
        Intent serviceIntent = new Intent(this, FirestoreService.class);
        this.startService(serviceIntent);

        //Get firestore token ID
        FirebaseInstanceId.getInstance().getInstanceId()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.w(TAG, "getInstanceId failed", task.getException());
                        return;
                    }
                    // Get new Instance ID token
                    String token = task.getResult().getToken();
                    Log.d(TAG, token);
                });
        initializePlacesClient();
        //Retrieve a unique device id.
        deviceId = new DeviceUtils().getDeviceId(getContentResolver());

        //Search place.
        searchView = findViewById(R.id.search_location);
        //Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {

            @Override
            public boolean onQueryTextSubmit(String s) {
                return onSearchStore();
            }

            @Override
            public boolean onQueryTextChange(String s) {
                return false;
            }
        });

        //Create floating button.
        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(view -> showNearbyPlaces());
    }

    private boolean onSearchStore() {
        String searchInput = searchView.getQuery().toString();

        if (searchInput.equals("")) {
            return false;
        }
        FindAutocompletePredictionsRequest request = FindAutocompletePredictionsRequest.builder()
                .setQuery(searchInput)
                .setCountry("IE").build();
        Task<FindAutocompletePredictionsResponse> autocompletePredictions = placesClient.findAutocompletePredictions(request);

        autocompletePredictions.addOnSuccessListener(findAutocompletePredictionsResponse -> {
            List<AutocompletePrediction> prediction = findAutocompletePredictionsResponse.getAutocompletePredictions();

            AutocompletePrediction autocompletePrediction = prediction.get(0);
            //Retrieve the store if exist
            String placeId = autocompletePrediction.getPlaceId();

            storeService.getById(placeId, documentSnapshot -> {
                if (documentSnapshot.exists()) {
                    List<Map<String, Object>> queueRecords = (List<Map<String, Object>>) documentSnapshot.get("queueRecords");
                    GeoPoint coordinates = documentSnapshot.getGeoPoint("coordinates");
                    String name = (String) documentSnapshot.get("name");
                    LatLng latLng = new LatLng(coordinates.getLatitude(), coordinates.getLongitude());
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("Number of people in the queue reported:").append(System.lineSeparator());
                    int count = Math.max(queueRecords.size() - 3, 0);
                    for (int i = queueRecords.size() - 1; i >= count; i--) {
                        getRecordsDate(stringBuilder, queueRecords.get(i));
                    }
                    stringBuilder.append(System.lineSeparator()).append(System.lineSeparator()).append("Click here to subscribe!");
                    reportMarker = addMarker(name, stringBuilder.toString(), latLng);
                    reportMarker.setTag(documentSnapshot);
                    reportMarker.showInfoWindow();

                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17));
                } else {
                    Toast.makeText(getApplicationContext(), "Sorry... We haven't received reports for this store yet!", Toast.LENGTH_LONG).show();
                }
            });
        });
        return false;
    }

    //Convert the timestamp to date.
    private void getRecordsDate(StringBuilder stringBuilder, Map<String, Object> fields) {
        long length = (long) fields.get("length");
        String pattern = "dd-MM HH:mm";
        Timestamp timestamp = (Timestamp) fields.get("date");
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern, Locale.getDefault());
        String date = simpleDateFormat.format(timestamp.toDate());
        stringBuilder.append(date).append(" ").append(":").append(" ").append(length).append(System.lineSeparator());
    }

    private void removeMarker() {
        if (reportMarker != null) {
            reportMarker.hideInfoWindow();
            reportMarker.remove();
        }
    }

    //Retrieve user points.
    private void registerUserDataChangesListener() {
        userService.listenForChanges(deviceId, (documentSnapshot, e) -> {
            if (documentSnapshot != null && documentSnapshot.exists()) {
                currentPoints = (long) documentSnapshot.get("points");
                levelEntity = levelService.getUserLevel(currentPoints);
                TextView totalPoints = findViewById(R.id.total_points);
                totalPoints.setText(String.valueOf(currentPoints));
                Log.d(TAG, "Level successfully loaded");
            }
        });
    }

    //Construct a PlacesClient.
    private void initializePlacesClient() {
        Places.initialize(getApplicationContext(), getString(R.string.google_maps_key));
        placesClient = Places.createClient(this);
        //Construct a FusedLocationProviderClient.
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
    }

    //Set up the options menu.
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.current_place_menu, menu);
        getSupportActionBar().setHomeAsUpIndicator(R.drawable.logo_small);// set drawable icon
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("");
        getSupportActionBar().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#2c5aa0")));

        //This is called here as the menu needs to be ready
        registerUserDataChangesListener();
        return true;
    }

    //Retrieve avatar level.
    private int getLevelAvatarImage(LevelEntity level) {
        switch (level.getDescription()) {
            case "level 1":
                return R.drawable.level1;
            case "level 2":
                return R.drawable.level2;
            case "level 3":
                return R.drawable.level3;
            case "level 4":
                return R.drawable.level4;
            case "level 5":
                return R.drawable.level5;
            case "level 6":
                return R.drawable.level6;
        }
        return 0;
    }

    //Handles a click on the menu option to get a place.
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.string.input:
                showNearbyPlaces();
                return true;
            case R.id.store_access:
                onStoreAccessClick();
                return true;
            case R.id.level:
                onLevelClick();
                return true;
            default:
                //The user's action was not recognized.
                return super.onOptionsItemSelected(item);
        }
    }

    private void onLevelClick() {
        //User click on the "diamond" icon, it shows a pop-up message with an avatar level.
        AlertDialog.Builder dialogLevel = new AlertDialog.Builder(MapsActivity.this);
        LayoutInflater avatar = LayoutInflater.from(MapsActivity.this);
        final View view = avatar.inflate(R.layout.avatar_dialog, null);
        ImageView avatarImg = view.findViewById(R.id.img_level);
        TextView title = view.findViewById(R.id.avatar_title);

        title.setText("You are in " + levelEntity.getDescription());
        AlertDialog alertDialog = dialogLevel.create();
        Button btnClose = view.findViewById(R.id.btn_close);
        btnClose.setOnClickListener(view1 -> alertDialog.dismiss());

        avatarImg.setImageResource(getLevelAvatarImage(levelEntity));
        alertDialog.setView(view);
        alertDialog.show();
    }

    private void onStoreAccessClick() {
        // User choose the "Store Access" item, show the store functionality.
        AlertDialog access = new AlertDialog.Builder(MapsActivity.this).create();
        access.setTitle("Access Control");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        access.setView(input);
        access.setButton(AlertDialog.BUTTON_POSITIVE, "OK", (dialog, which) -> db.collection("access")
                .whereEqualTo("secretKey", input.getText().toString()).get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!queryDocumentSnapshots.isEmpty()) {
                        DocumentSnapshot documentSnapshot = queryDocumentSnapshots.getDocuments().get(0);
                        Intent storeIntent = new Intent(MapsActivity.this, StoreActivity.class);
                        storeIntent.putExtra("storeId", (String) documentSnapshot.get("storeId"));
                        startActivity(storeIntent);
                    } else {
                        Toast.makeText(getApplicationContext(), "Your secret key is incorrect. Please try again!", Toast.LENGTH_LONG).show();
                    }
                }));
        access.setButton(AlertDialog.BUTTON_NEGATIVE, "CANCEL", (dialog, which) -> {
            Toast.makeText(getApplicationContext(), "You clicked on cancel", Toast.LENGTH_LONG).show();
            dialog.cancel();
        });
        access.show();
    }

    //If Google Play services is not installed on the device, the user will be prompted to install it inside the SupportMapFragment.
    //This method will only be triggered once the user has installed Google Play services and returned to the app.
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (map != null) {
            outState.putParcelable(KEY_CAMERA_POSITION, map.getCameraPosition());
            outState.putParcelable(KEY_LOCATION, lastLocation);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onMapReady(GoogleMap map) {
        this.map = map;
        this.map.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {

            @Override
            public View getInfoWindow(Marker arg0) {
                return null;
            }

            @Override
            public View getInfoContents(Marker marker) {

                View infoWindow = getLayoutInflater().inflate(R.layout.custom_info_contents, findViewById(R.id.map), false);

                TextView title = infoWindow.findViewById(R.id.title);
                title.setText(marker.getTitle());

                TextView snippet = infoWindow.findViewById(R.id.snippet);
                snippet.setText(marker.getSnippet());

                return infoWindow;
            }
        });
        map.setOnInfoWindowClickListener(this);
        //Prompt the user for permission.
        getLocationPermission();
        //Turn on the My Location layer and the related control on the map.
        updateLocationUI();
        //Get the current location of the device and set the position of the map.
        getDeviceLocation();
    }

    //Gets the current location of the device, and positions the map's camera.
    private void getDeviceLocation() {
        //Get the best and most recent location of the device, which may be null in rare cases when a location is not available.
        try {
            if (locationPermissionGranted) {
                Task<Location> locationResult = fusedLocationProviderClient.getLastLocation();
                locationResult.addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        //Set the map's camera position to the current location of the device.
                        lastLocation = task.getResult();
                        if (lastLocation != null) {
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()), DEFAULT_ZOOM));
                        }
                    } else {
                        Log.d(TAG, "Current location is null. Using defaults.");
                        Log.e(TAG, "Exception: %s", task.getException());
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, DEFAULT_ZOOM));
                        map.getUiSettings().setMyLocationButtonEnabled(false);
                    }
                });
            }
        } catch (SecurityException e) {
            Log.e("Exception: %s", e.getMessage(), e);
        }
    }

    //Prompts the user for permission to use the device location.
    private void getLocationPermission() {
        if (ContextCompat.checkSelfPermission(this.getApplicationContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationPermissionGranted = true;
        } else {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }
    }

    //Handles the result of the request for location permissions.
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        locationPermissionGranted = false;
        if (requestCode == PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION) {// If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                locationPermissionGranted = true;
            }
        }
        updateLocationUI();
    }

    //Prompts the user to select the current place from a list of likely places, and shows the current place on the map.
    private void showNearbyPlaces() {
        if (map == null) {
            return;
        }
        if (locationPermissionGranted) {
            final Task<FindCurrentPlaceResponse> placeResult = retrieveCurrentPlaces();
            placeResult.addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    final FindCurrentPlaceResponse likelyPlaces = task.getResult();
                    final Place place = likelyPlaces.getPlaceLikelihoods().get(0).getPlace();
                    final String storeName = place.getName();
                    Log.i("Place Id", String.valueOf(place.getId()));
                    new AlertDialog.Builder(MapsActivity.this)
                            .setTitle("Location")
                            .setMessage(getString(R.string.are_you_in_the_store_name, storeName))
                            .setPositiveButton(R.string.yes, (dialog, which) -> {
                                //Select the current store
                                StoreDetails store = new StoreDetails(place.getId(), place.getName(), place.getAddress(), place.getLatLng(), place.getAttributions());
                                selectPlaceOnMap(store);
                                showQueueInputDialog(store);

                            })
                            .setNegativeButton(R.string.no, (dialog, which) -> filterStoresAndShowPlacesDialog(likelyPlaces))
                            .setIcon(R.drawable.marker)
                            .show();

                } else {
                    Log.e(TAG, "Exception: %s", task.getException());
                }
            });
        } else {
            //The user has not granted permission.
            Log.i(TAG, "The user did not grant location permission.");
            //Add a default marker, because the user hasn't selected a place.

            reportMarker = addMarker(getString(R.string.default_info_title), getString(R.string.default_info_snippet), defaultLocation);

            //Prompt the user for permission.
            getLocationPermission();
        }
    }

    //Create store record.
    private void saveUserInput(StoreDetails storeInput, int value) {
        //create queue record object.
        QueueRecordEntity queueRecord = new QueueRecordEntity();
        queueRecord.setDate(new Date());
        queueRecord.setLength(value);
        queueRecord.setUserId(deviceId);

        userService.updateField(deviceId, "points", FieldValue.increment(5));

        //Create a new store
        StoreEntity store = new StoreEntity();
        store.setName(storeInput.getName());
        store.setAddress(storeInput.getAddress());
        LatLng coord = storeInput.getCoordinates();
        store.setCoordinates(new GeoPoint(coord.latitude, coord.longitude));
        store.setQueueRecords(Collections.singletonList(queueRecord));
        //Retrieve stores
        storeService.getById(storeInput.getId(), documentSnapshot -> {
            if (documentSnapshot.exists()) {
                Log.i("Retrieving document", "Document already created");
                storeService.updateField(documentSnapshot.getId(), "queueRecords", FieldValue.arrayUnion(queueRecord));
            } else {

                //Add a new store with a generated ID.
                storeService.update(storeInput.getId(), store, new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d("Adding store", "DocumentSnapshot added with ID: " + storeInput.getId());
                    }
                });
            }
        });
    }

    //Display a pop-up message allowing the user to contribute to the App.
    private void showQueueInputDialog(StoreDetails store) {
        NumberPicker picker = new NumberPicker(MapsActivity.this);
        picker.setMinValue(0);
        picker.setMaxValue(100);
        final AlertDialog.Builder builder = new AlertDialog.Builder(MapsActivity.this);
        builder.setMessage(getString((R.string.how_many_people_are_in_the_queue)));
        builder.setView(picker);

        builder.setPositiveButton("Ok", (dialog, id) -> {
            String newLevel = "";
            if (!levelService.getUserLevel(currentPoints + 5).equals(levelEntity)) {
                newLevel = "Congratulations! You've moved to the next level. Check details in the diamond icon!";
            }
            saveUserInput(store, picker.getValue());

            final AlertDialog.Builder builder1 = new AlertDialog.Builder(MapsActivity.this);
            builder1.setMessage(getString(R.string.thank_you, newLevel));
            builder1.show();
        });
        builder.setNegativeButton("Cancel", (dialog, id) -> Toast.makeText(getApplicationContext(), "Cancel Pressed", Toast.LENGTH_LONG).show());
        builder.show();
    }

    //Displays a form allowing the user to select a place from a list of likely places.
    private void filterStoresAndShowPlacesDialog(FindCurrentPlaceResponse likelyPlaces) {
        //Set the count, handling cases where less than 30 entries are returned.
        int count = Math.min(likelyPlaces.getPlaceLikelihoods().size(), M_MAX_ENTRIES);

        List<StoreDetails> storeDetailsList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PlaceLikelihood placeLikelihood = likelyPlaces.getPlaceLikelihoods().get(i);
            Place place = placeLikelihood.getPlace();
            StoreDetails store = new StoreDetails(place.getId(), place.getName(), place.getAddress(), place.getLatLng(), place.getAttributions());
            storeDetailsList.add(store);
        }
        //Show a dialog offering the user the list of likely places, and add a marker at the selected place.
        //Ask the user to choose the place where they are now.
        DialogInterface.OnClickListener listener = (dialog, which) -> {
            // The "which" argument contains the position of the selected item.
            StoreDetails store = storeDetailsList.get(which);
            selectPlaceOnMap(store);
            showQueueInputDialog(store);
        };
        map.clear();
        String[] names = storeDetailsList
                .stream() //Calls the stream API
                .map(StoreDetails::getName) //Perform a transformation using the map method
                .toArray(String[]::new); //Convert to array

        //Display a list of places
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_place)
                .setItems(names, listener)
                .show();
    }

    @SuppressLint("MissingPermission")
    private Task<FindCurrentPlaceResponse> retrieveCurrentPlaces() {
        //Use fields to define the data types to return.
        List<Place.Field> placeFields = Arrays.asList(Place.Field.NAME, Place.Field.ADDRESS, Place.Field.LAT_LNG, Place.Field.ID);
        //Use the builder to create a FindCurrentPlaceRequest.
        FindCurrentPlaceRequest request = FindCurrentPlaceRequest.newInstance(placeFields);
        //Get the likely places - that is, the businesses and other points of interest that are the best match for the device's current location.
        return placesClient.findCurrentPlace(request);
    }

    private void selectPlaceOnMap(StoreDetails store) {
        LatLng markerLatLng = store.getCoordinates();
        String markerSnippet = store.getAddress();
        if (store.getAttributions() != null) {
            markerSnippet = markerSnippet + "\n" + store.getAttributions();
        }

        //Add a marker for the selected place, with an info window showing information about that place.
        reportMarker = addMarker(store.getName(), markerSnippet, markerLatLng);

        //Position the map's camera at the location of the marker.
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(markerLatLng, DEFAULT_ZOOM));
    }


    private Marker addMarker(String title, String snippet, LatLng coordinates) {
        removeMarker();
        return map.addMarker(new MarkerOptions().title(title).snippet(snippet).position(coordinates));
    }

    //Updates the map's UI settings based on whether the user has granted location permission.
    private void updateLocationUI() {
        if (map == null) {
            return;
        }
        try {
            if (locationPermissionGranted) {
                map.setMyLocationEnabled(true);
                map.getUiSettings().setMyLocationButtonEnabled(true);
            } else {
                map.setMyLocationEnabled(false);
                map.getUiSettings().setMyLocationButtonEnabled(false);
                lastLocation = null;
                getLocationPermission();
            }
        } catch (SecurityException e) {
            Log.e("Exception: %s", e.getMessage());
        }
    }

    //Subscription to receive queue length notification
    @Override
    public void onInfoWindowClick(Marker marker) {
        DocumentSnapshot doc = (DocumentSnapshot) marker.getTag();
        new AlertDialog.Builder(MapsActivity.this)
                .setTitle("Subscription")
                .setMessage("Do you want to be notified when there are changes in the queue length?")
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    QueueSubscriptionEntity subscription = new QueueSubscriptionEntity();
                    subscription.setDate(new Date());
                    subscription.setStoreId(doc.getId());
                    userService.updateField(deviceId, "queueSubscription", FieldValue.arrayUnion(subscription));

                    Toast toast = Toast.makeText(getApplicationContext(), "Subscription successful!", Toast.LENGTH_LONG);
                    toast.setGravity(Gravity.CENTER, 0, 0);
                    toast.show();
                })
                .setNegativeButton(R.string.no, (dialog, which) -> {
                    // Do nothing
                })
                .setIcon(R.drawable.bell)
                .show();
    }
}