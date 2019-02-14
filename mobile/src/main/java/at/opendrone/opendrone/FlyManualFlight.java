package at.opendrone.opendrone;


import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.DrawerLayout;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;

import at.opendrone.opendrone.network.ConnectDisconnectTasks;
import at.opendrone.opendrone.network.OpenDroneFrame;
import at.opendrone.opendrone.network.TCPHandler;
import at.opendrone.opendrone.network.TCPMessageReceiver;
import at.opendrone.opendrone.raspistats.RaspiStat;
import at.opendrone.opendrone.raspistats.RaspiStatParser;
import io.github.controlwear.virtual.joystick.android.JoystickView;
import uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt;

/**
 * A simple {@link Fragment} subclass.
 */
public class FlyManualFlight extends Fragment implements TCPMessageReceiver {
    private static final String TAG = "manualFlighty";
    private static final String TAG_ERROR = "errortcpreceive";
    private static final int MIN_MOTOR_VALUE = 1000;
    private static final int MAX_MOTOR_VALUE = 2000;

    private View view;
    private JoystickView throttle;
    private JoystickView direction;

    private DrawerLayout.DrawerListener listener;

    private TextView errorTxtView;
    private ImageButton homeBtn;
    private ImageButton stopRotorBtn;
    private ImageButton changeViewBtn;
    private MapView mapView;
    private FrameLayout cameraView;

    private boolean mapViewShown = false;

    private SharedPreferences sp;

    private RaspiStatParser parser;

    private Location droneLocation;
    private Location userLocation;

    private boolean mRequestingLocationUpdates = false;

    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private LocationRequest mLocationRequest;

    private int[] codes;
    private String[] data;

    private ConnectDisconnectTasks tasks = ConnectDisconnectTasks.getInstance();

    public FlyManualFlight() {
        // Required empty public constructor
    }

    @Override
    public void onResume() {
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        ((MainActivity) getActivity()).closeDrawer();
        super.onResume();

        tasks.setMessageReceiver(this);

        if (mapView != null) {
            mapView.onResume();
        }

        if (mRequestingLocationUpdates) {
            startLocationUpdates();
        }

        listener = new DrawerLayout.DrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View view, float v) {
                throttle.setEnabled(false);
                direction.setEnabled(false);
                throttle.resetButtonPosition();
                direction.resetButtonPosition();
                throttle.setEnabled(true);
                direction.setEnabled(true);
            }

            @Override
            public void onDrawerOpened(@NonNull View view) {
                throttle.setEnabled(false);
                direction.setEnabled(false);
                throttle.resetButtonPosition();
                direction.resetButtonPosition();
            }

            @Override
            public void onDrawerClosed(@NonNull View view) {
                throttle.resetButtonPosition();
                direction.resetButtonPosition();
                throttle.setEnabled(true);
                direction.setEnabled(true);
            }

            @Override
            public void onDrawerStateChanged(int i) {
                throttle.resetButtonPosition();
                direction.resetButtonPosition();
            }
        };
        ((MainActivity) getActivity()).drawerLayout.addDrawerListener(listener);
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        tasks.removeMessageReceiver();
        if (mapView != null) {
            mapView.onPause();
        }

        Log.i(TAG, "OnPause");
        ((MainActivity) getActivity()).drawerLayout.removeDrawerListener(listener);
        stopLocationUpdates();
    }

    private void loadConfig() {
        Context ctx = getActivity().getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
    }

    private void configureMap() {
        mapView.setMinZoomLevel(7.0);
        mapView.setHorizontalMapRepetitionEnabled(true);
        mapView.setVerticalMapRepetitionEnabled(false);
        mapView.setScrollableAreaLimitLatitude(MapView.getTileSystem().getMaxLatitude(), MapView.getTileSystem().getMinLatitude(), 0);
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        loadConfig();
        view = inflater.inflate(R.layout.fragment_fly_manual_flight, container, false);
        sp = getActivity().getSharedPreferences("at.opendrone.opendrone", Context.MODE_PRIVATE);
        //initStrings();
        setRetainInstance(true);
        Log.i(TAG, "onCreate");
        findViews();
        configureMap();
        initJoysticks();

        stopAnimateErrorText();
        checkShowTargetPrompt();

        if(tasks.isArmed()){
            arm();
        }else{
            unarm();
        }
        //Try to connect!

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        getActivity().runOnUiThread(this::requestPermissionAndSetLocation);

        getActivity().runOnUiThread(this::startInitMapViewThread);
    }

    private void setDroneLocation(Location location) {
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        Log.i(TAG, lat + "/ " + lng);
        IMapController mapController = mapView.getController();
        mapController.setZoom(17);
        GeoPoint startPoint = new GeoPoint(lat, lng);
        mapController.setCenter(startPoint);
        setUserAndDroneMarker(userLocation, location);
    }

    private void setDroneMarker(Location location) {
        GeoPoint p = new GeoPoint(location);
        Marker m = new Marker(mapView);
        m.setPosition(p);
        m.setIcon(getResources().getDrawable(R.drawable.drone_map_icon));
        //Do nothing on click
        m.setOnMarkerClickListener((marker, mapView) -> false);
        mapView.getOverlayManager().add(m);
    }

    private void setUserAndDroneMarker(Location userLocation, Location droneLocation) {
        mapView.getOverlayManager().clear();
        setUserMarker(userLocation);
        setDroneMarker(droneLocation);

        Log.i(TAG, mapView.getOverlayManager().size() + "");
    }

    private void fillCodeArray(int... codes) {
        this.codes = codes;
    }

    private void fillDataArray(String... data) {
        this.data = data;
    }

    private void fillDataArray(int... data) {
        String[] strData = new String[data.length];
        for (int i = 0; i < data.length; i++) {
            strData[i] = data[i] + "";
        }
        fillDataArray(strData);
    }

    private void initJoysticks() {
        throttle = view.findViewById(R.id.throttleStick);
        throttle.setOnMoveListener((angle, strength) -> {
            int[][] cmd = interpretThrottleStick(throttle, angle, strength);
            fillCodeArray((byte) cmd[0][0], (byte) cmd[1][0]);
            fillDataArray(cmd[0][1], cmd[1][1]);
            if (tasks.isArmed()) {
                sendData(data, codes);
            }
        });

        direction = view.findViewById(R.id.directionStick);
        direction.setOnMoveListener((angle, strength) -> {
            int[][] cmd = interpretDirectionStick(direction, angle, strength);
            fillCodeArray((byte) cmd[0][0], (byte) cmd[1][0]);
            fillDataArray(cmd[0][1], cmd[1][1]);
            if (tasks.isArmed()) {
                sendData(data, codes);
            }

        });

    }

    private void sendData(String[] data, int[] codes) {
        try {
            OpenDroneFrame f = new OpenDroneFrame((byte) 1, data, codes);
            tasks.sendMessage(f.toString());
        } catch (Exception ex) {
            Log.e(TAG_ERROR, "OpenDroneFrameError", ex);
        }
    }

    private void interpretData(String raw) {
        RaspiStat stat = parser.parse(raw);
        stat.doStuff();
    }

    private void showErrorMessage(String text) {
        Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show();
    }

    private double parseDouble(String string) {
        try {
            return Double.parseDouble(string);
        } catch (Exception ex) {
            Log.e(TAG_ERROR, "ERROR", ex);
            return -1d;
        }
    }

    private void updateTextViews(TextView txtView, String format, String... values) {
        txtView.setText(String.format(format, values));
    }

    private void updatePosition(double lat, double lng) {
        droneLocation.setLatitude(lat);
        droneLocation.setLongitude(lng);
        setDroneLocation(droneLocation);
    }

    private void checkShowTargetPrompt() {
        boolean isHomeTutorialShown = sp.getBoolean(R.id.homeBtn + "", false);
        boolean isChangeViewTutorialShown = sp.getBoolean(R.id.changeViewBtn + "", false);
        boolean isArmStopRotorTutorialShown = sp.getBoolean(R.id.stopRotorBtn + "", false);

        if (!isHomeTutorialShown) {
            showTargetPrompt(R.id.homeBtn);
            return;
        }
        if (!isChangeViewTutorialShown) {
            showTargetPrompt(R.id.changeViewBtn);
            return;
        }
        if (!isArmStopRotorTutorialShown) {
            showTargetPrompt(R.id.stopRotorBtn);
            return;
        }
    }

    private void showTargetPrompt(int targetID) {
        Log.i(TAG, "target");
        new MaterialTapTargetPrompt.Builder(getActivity())
                .setTarget(targetID)
                .setPrimaryText("Send your first email")
                .setSecondaryText("Tap the envelope to start composing your first email")
                .setPromptStateChangeListener((prompt, state) -> {
                    if (state == MaterialTapTargetPrompt.STATE_FOCAL_PRESSED) {
                        sp.edit().putBoolean(targetID + "", true).apply();
                        //checkShowTargetPrompt();
                    }
                })
                .show();
    }

    private void findViews() {
        homeBtn = view.findViewById(R.id.homeBtn);
        changeViewBtn = view.findViewById(R.id.changeViewBtn);
        stopRotorBtn = view.findViewById(R.id.stopRotorBtn);
        mapView = view.findViewById(R.id.mapView);
        cameraView = view.findViewById(R.id.cameraView);
        errorTxtView = view.findViewById(R.id.errorTxtView);

        parser = new RaspiStatParser(view, getContext());

        homeBtn.setOnClickListener(v -> displayHomeConfirmationDialog());
        stopRotorBtn.setOnClickListener(v -> sendArmOrAbortMessage());
        changeViewBtn.setOnClickListener(v -> changeView());
    }

    private void startInitMapViewThread() {
        getActivity().runOnUiThread(() -> initMapView());
    }

    private void initMapView() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);

        mapView.setBuiltInZoomControls(false);
        mapView.setMultiTouchControls(true);

        droneLocation = new Location("GPS");
        droneLocation.setLatitude(OpenDroneUtils.DEFAULT_LAT);
        droneLocation.setLongitude(OpenDroneUtils.DEFAULT_LNG);
        setDroneLocation(droneLocation);
    }

    private void changeView() {
        if (mapViewShown) {
            hideMapView();
        } else {
            showMapView();
        }
    }

    private void setImageBtnImage(ImageButton btn, int drawableID) {
        btn.setImageDrawable(getResources().getDrawable(drawableID));
    }

    private void showMapView() {
        mapView.setVisibility(View.VISIBLE);
        setImageBtnImage(changeViewBtn, R.drawable.ic_view_live_feed);
        mapViewShown = true;
    }

    private void hideMapView() {
        mapView.setVisibility(View.GONE);
        setImageBtnImage(changeViewBtn, R.drawable.ic_view_map);
        mapViewShown = false;
    }

    private void displayStopRotorDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(getString(R.string.stop_rotor_alarm_message))
                .setPositiveButton(getString(R.string.stop_rotor_alarm_pos), (dialog, id) -> sendAbortMessage())
                .setNegativeButton(getString(R.string.stop_rotor_alarm_neg), (dialog, which) -> dialog.dismiss());
        builder.create().show();
    }

    private void displayHomeConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(getString(R.string.go_home_alarm_message))
                .setPositiveButton(getString(R.string.go_home_alarm_pos), (dialog, id) -> sendGoHomeMessage())
                .setNegativeButton(getString(R.string.go_home_alarm_neg), (dialog, id) -> dialog.dismiss());
        builder.create().show();
        // Create the AlertDialog object and return it
    }

    private void sendGoHomeMessage() {
        fillDataArray(1);
        fillCodeArray(OpenDroneUtils.CODE_GO_HOME);
        sendData(data, codes);
    }

    private void sendArmOrAbortMessage() {
        if (tasks.isArmed()) {
            displayStopRotorDialog();
        } else {
            sendArmMessage();
            arm();
        }
    }

    private void sendArmMessage() {
        fillCodeArray(OpenDroneUtils.CODE_ARM);
        fillDataArray(1);
        sendData(data, codes);
    }

    private void arm(){
        Log.i(TAG, "ARM");
        ((MainActivity) getActivity()).canOpenDrawer = false;
        setImageBtnImage(stopRotorBtn, R.drawable.ic_stoprotor);
        tasks.setArmed(true);
    }

    private void unarm(){
        Log.i(TAG, "UNARM");
        setImageBtnImage(stopRotorBtn, R.drawable.ic_arm);
        ((MainActivity) getActivity()).canOpenDrawer = true;
        tasks.setArmed(false);
    }

    private void sendAbortMessage() {
        fillDataArray(1);
        fillCodeArray(OpenDroneUtils.CODE_ABORT);
        sendData(data, codes);
        unarm();
    }

    private int getPercentFromSticks(int center, int value){
        int percent = center+value/2;
        if(percent < 0){
            percent = 0;
        }
        if(percent>100){
            percent = 100;
        }
        return percent;
    }

    private int[][] interpretThrottleStick(JoystickView stick, int angle, int strength) {
        int[][] values = new int[2][2];
        double rad = angle * Math.PI / 180;
        int percent;
        int powerDifference = MAX_MOTOR_VALUE - MIN_MOTOR_VALUE;

        //Calculation for the x-axis
        double hypothenusis = strength;
        int adjacentX = (int) (Math.cos(rad) * hypothenusis);
        percent = getPercentFromSticks(50, adjacentX);
        values[0][0] = OpenDroneUtils.CODE_YAW;
        values[0][1] = MIN_MOTOR_VALUE+(int)(powerDifference * (percent/100.0));
        /*if (adjacentX < 0) {
            //returnValue += OpenDroneUtils.CODE_YAW_LEFT + "," + (adjacentX*(-1)) + ";";
            values[0][0] = OpenDroneUtils.CODE_YAW_LEFT;
            values[0][1] = (adjacentX * (-1));
        } else {
            //returnValue += OpenDroneUtils.CODE_YAW_RIGHT + "," + adjacentX + ";";
            values[0][0] = OpenDroneUtils.CODE_YAW_RIGHT;
            values[0][1] = adjacentX;
        }*/

        //Calculation for the y-axis
        int opposite = (int) (Math.sin(rad) * hypothenusis);
        //values[1][1] = opposite;
        percent = getPercentFromSticks(50, opposite);

        values[1][0] = OpenDroneUtils.CODE_THROTTLE;
        values[1][1] = MIN_MOTOR_VALUE+(int)(powerDifference * (percent/100.0));
        /*if (opposite < 0) {
            //returnValue += OpenDroneUtils.CODE_THROTTLE_DOWN + "," + (opposite*(-1)) + ";";
            values[1][0] = OpenDroneUtils.CODE_THROTTLE_DOWN;
            values[1][1] = (opposite * (-1));
        } else {
            //returnValue += OpenDroneUtils.CODE_THROTTLE_UP + "," + opposite + ";";
            values[1][0] = OpenDroneUtils.CODE_THROTTLE_UP;
            values[1][1] = opposite;
        }*/

        Log.i("manualFlightys", values[1][1] + "");

        return values;
    }

    private int[][] interpretDirectionStick(JoystickView stick, int angle, int strength) {
        int[][] values = new int[2][2];
        double rad = angle * Math.PI / 180;

        //Calculation for the x-axis
        double hypothenusis = strength;
        int adjacentX = (int) (Math.cos(rad) * hypothenusis);
        if (adjacentX < 0) {
            values[0][0] = OpenDroneUtils.CODE_ROLL_LEFT;
            values[0][1] = (adjacentX * (-1));
            //returnValue += OpenDroneUtils.CODE_ROLL_LEFT + "," + (adjacentX*(-1)) + ";";
        } else {
            values[0][0] = OpenDroneUtils.CODE_ROLL_RIGHT;
            values[0][1] = adjacentX;
            //returnValue += OpenDroneUtils.CODE_ROLL_RIGHT + "," + adjacentX + ";";
        }

        //Calculation for the y-axis
        int opposite = (int) (Math.sin(rad) * hypothenusis);
        if (opposite < 0) {
            values[1][0] = OpenDroneUtils.CODE_PITCH_BACKWARD;
            values[1][1] = (opposite * (-1));
            //returnValue += OpenDroneUtils.CODE_PITCH_BACKWARD + "," + (opposite*(-1)) + ";";
        } else {
            values[1][0] = OpenDroneUtils.CODE_PITCH_FORWARD;
            values[1][1] = opposite;
            //returnValue += OpenDroneUtils.CODE_PITCH_FORWARD + "," + opposite + ";";
        }
        return values;
    }

    private void animateErrorText(String error) {
        errorTxtView.setText(String.format(getString(R.string.error_txt), error));
        AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
        AlphaAnimation fadeOut = new AlphaAnimation(1.0f, 0.0f);
        fadeIn.setRepeatCount(Animation.INFINITE);
        fadeOut.setRepeatCount(Animation.INFINITE);
        fadeIn.setDuration(500);
        fadeOut.setDuration(1200);
        errorTxtView.setVisibility(View.VISIBLE);
        errorTxtView.startAnimation(fadeIn);
        errorTxtView.startAnimation(fadeOut);
    }

    private void stopAnimateErrorText() {
        errorTxtView.clearAnimation();
        errorTxtView.setVisibility(View.GONE);
    }

    // ====================================== STUFF FOR LOCATION ======================================

    /**
     * Only call this method when you have GPS permissions
     * starts the location updates with a callback and a locationrequest
     */
    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        mFusedLocationClient.requestLocationUpdates(mLocationRequest,
                mLocationCallback,
                null /* Looper */);
    }

    private void stopLocationUpdates() {
        try {
            mFusedLocationClient.removeLocationUpdates(mLocationCallback);
        } catch (Exception ex) {
            Log.e("flightplany", "error", ex);
        }
    }

    private boolean checkGPSPermission() {
        return ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(10000);
        mLocationRequest.setFastestInterval(5000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    private void initLocationThings() {
        initLocationManager();
        createLocationRequest();
        initLocationCallback();
        mRequestingLocationUpdates = true;
        setLastKnownLocation();
    }

    private void setUserMarker(Location location) {
        if (location == null) {
            Log.i(TAG, "BS");
            return;
        }
        Log.i(TAG, "not BS");
        GeoPoint p = new GeoPoint(location);
        Marker m = new Marker(mapView);
        m.setIcon(getResources().getDrawable(R.drawable.ic_home_primary_color));
        m.setPosition(p);
        m.setOnMarkerClickListener((marker, mapView) -> {
            displayHomeConfirmationDialog();
            return false;
        });
        mapView.getOverlayManager().add(m);
    }

    public void setUserLocation(Location location) {
        userLocation = location;
        setUserAndDroneMarker(location, droneLocation);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case OpenDroneUtils.RQ_GPS: {
                if (grantResults.length <= 1) {
                    return;
                }
                if (grantResults.length > 1
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED || grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    initLocationThings();
                }
                return;
            }
            default: {
                Toast.makeText(getContext(), "Didn't grant all Permissions", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestPermissionAndSetLocation() {
        if (!checkGPSPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    OpenDroneUtils.RQ_GPS);
        } else {
            initLocationThings();
        }
    }

    /**
     * Only call this method when you have GPS permissions
     * Sets the last known position.
     */
    @SuppressLint("MissingPermission")
    private void setLastKnownLocation() {
        mFusedLocationClient.getLastLocation()
                .addOnSuccessListener(getActivity(), new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(Location location) {
                        if (location != null) {
                            Log.i(TAG, location.getLatitude() + "/ " + location.getLongitude());
                            setUserLocation(location);
                        } else {
                            Log.i(TAG, "No Location.");
                        }
                    }
                }).addOnFailureListener(getActivity(), new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Toast.makeText(getActivity(), getResources().getString(R.string.exception_sorry), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initLocationManager() {
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(getActivity());
    }

    private void initLocationCallback() {
        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                Location bestLocation = null;
                for (Location location : locationResult.getLocations()) {
                    if (bestLocation == null || location.getAccuracy() < bestLocation.getAccuracy()) {
                        bestLocation = location;
                    }
                }
                setUserLocation(bestLocation);
            }
        };
    }

    @Override
    public void onMessageReceived(String... values) {
        interpretData(values[0]);
    }

    /**
     * Disconnects using a background task to avoid doing long/network operations on the UI thread
     */
    /*@SuppressLint("StaticFieldLeak")
    public class DisconnectTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {

            // disconnect
            mTCPHandler.stopClient();
            mTCPHandler = null;

            Log.i(TAG, (mTCPHandler == null) + "");

            return null;
        }

        @Override
        protected void onPostExecute(Void nothing) {
            super.onPostExecute(nothing);
        }
    }

    @SuppressLint("StaticFieldLeak")
    public class ConnectTask extends AsyncTask<String, String, TCPHandler> {

        @Override
        protected TCPHandler doInBackground(String... message) {

            //we create a TCPClient object and
            mTCPHandler = new TCPHandler(TARGET, PORT, new TCPHandler.OnMessageReceived() {
                @Override
                //here the messageReceived method is implemented
                public void messageReceived(String message) {
                    //this method calls the onProgressUpdate
                    publishProgress(message);
                }
            });
            mTCPHandler.run();

            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            Log.i(TAG, "RECEIVE: " + values[0]);
            interpretData(values[0]);
        }
    }*/

}
