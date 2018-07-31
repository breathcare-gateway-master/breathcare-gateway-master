package com.doctorsfab.breathcare.airflowgateway;

import android.Manifest;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.support.design.widget.Snackbar;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.exceptions.BleScanException;
import com.polidea.rxandroidble2.scan.ScanFilter;
import com.polidea.rxandroidble2.scan.ScanResult;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;

import com.jakewharton.rx.ReplayingShare;

import com.trello.rxlifecycle2.components.support.RxAppCompatActivity;

import static com.trello.rxlifecycle2.android.ActivityEvent.DESTROY;
import static com.trello.rxlifecycle2.android.ActivityEvent.PAUSE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class MainActivity extends RxAppCompatActivity {

    private RxBleClient rxBleClient;
    private Disposable scanDisposable;

    private RxBleDevice bleDevice = null;

    private String macAddress = null;

    private PublishSubject<Boolean> disconnectTriggerSubject = PublishSubject.create();
    private Observable<RxBleConnection> connectionObservable;

    private static final String TAG;

    public static final UUID UUID_HEART_RATE_MEASUREMENT;
    public static final UUID WX_CHAR_UUID;
    public static final UUID WX_NOTIFICATION_UUID;
    public static final UUID WX_SERVICE_UUID;
    public static final UUID CCCD;

    static {
        TAG = MainActivity.class.getSimpleName();
        UUID_HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
        WX_SERVICE_UUID = UUID.fromString("0000FF12-0000-1000-8000-00805f9b34fb");
        WX_CHAR_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805f9b34fb");
        WX_NOTIFICATION_UUID = UUID.fromString("0000FF02-0000-1000-8000-00805f9b34fb");
        CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    }

    private Timer timer = new Timer();
    private double graphLastXValue = 0d;
    private String graphLastTemp = "";

    private GraphView graph;
    private GraphView graph2;
    private GraphView graph3;

    private LineGraphSeries<DataPoint> mSeriesWind;
    private LineGraphSeries<DataPoint> mSeriesTemp;

    TextView textView_temp;
    TextView textView_wind;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2_sample);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1000);
        }

        rxBleClient = RxBleClient.create(this);

        graph = findViewById(R.id.graph1);
        graph2 = findViewById(R.id.graph2);
        graph3 = findViewById(R.id.graph3);

        textView_temp = findViewById(R.id.textview_Temp);
        textView_wind = findViewById(R.id.textview_Wind);

        mSeriesWind = new LineGraphSeries<>(new DataPoint[] {});
        mSeriesTemp = new LineGraphSeries<>(new DataPoint[] {});

        mSeriesWind.setColor(Color.RED);

        graph.addSeries(mSeriesWind);

        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(80);

        graph.getSecondScale().addSeries(mSeriesTemp);
        graph.getSecondScale().setMinY(15);
        graph.getSecondScale().setMaxY(35);
    }

    @Override
    protected void onPause(){
        super.onPause();

        if (isConnected()) {
            triggerDisconnect();
        }

        if (isScanning()) {
            scanDisposable.dispose();
        }
        timer.cancel();
        timer = null;
    }

    @Override
    protected void onResume(){
        super.onResume();

        if (!isConnected()) {
           startDeviceScan();
        }

        timer = new Timer();
        timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    Log.i(TAG, "status check timer");
                    if (!isConnected() && !isScanning()) {
                        startDeviceScan();
                    }
                }
            }, 1000, 2000);
    }

    private void startDeviceScan() {
        scanDisposable = rxBleClient.scanBleDevices(
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build(),
                new ScanFilter.Builder()
                        .build()
        )
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(this::dispose)
                .subscribe(this::onScanResult, this::onScanFailure);
    }

    private void onScanResult(ScanResult bleScanResult) {

        if (bleScanResult.getBleDevice().getName() != null &&
                bleScanResult.getBleDevice().getName().equals("UT363BT")) {

            Log.i(TAG, "UT363BT Found > " +
                    bleScanResult.getBleDevice().getName() + " " +
                    bleScanResult.getBleDevice().getMacAddress());

            Snackbar.make(findViewById(R.id.main),
                    bleScanResult.getBleDevice().getName() + " (" + bleScanResult.getBleDevice().getMacAddress() + ") Found",
                    Snackbar.LENGTH_LONG).show();

            macAddress = bleScanResult.getBleDevice().getMacAddress();
            scanDisposable.dispose();

            bleDevice = rxBleClient.getBleDevice(macAddress);
            bleDevice.observeConnectionStateChanges()
                    .compose(bindUntilEvent(DESTROY))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::onConnectionStateChange);

            connectionObservable = bleDevice.establishConnection(false)
                    .compose(bindUntilEvent(PAUSE))
                    .compose(ReplayingShare.instance());

            connectionObservable
                    .flatMap(rxBleConnection -> rxBleConnection.setupNotification(WX_NOTIFICATION_UUID))
                    .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                    .flatMap(notificationObservable -> notificationObservable)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::onNotificationReceived, this::onNotificationSetupFailure);

            connectionObservable
                    .flatMap(rxBleConnection -> Observable
                                    .interval(250, MILLISECONDS)
                                    .flatMapSingle(sequence -> rxBleConnection.writeCharacteristic(WX_CHAR_UUID, new byte[] { 94 })))
                    .observeOn(AndroidSchedulers.mainThread())
                    .takeUntil(disconnectTriggerSubject)
                    .subscribe(bytes -> onWriteSuccess(), this::onWriteFailure);

            connectionObservable
                    .flatMap(rxBleConnection -> // Set desired interval.
                            Observable.interval(5, SECONDS).flatMapSingle(sequence -> rxBleConnection.readRssi()))
                    .observeOn(AndroidSchedulers.mainThread())
                    .takeUntil(disconnectTriggerSubject)
                    .subscribe(this::updateRssi, this::onConnectionFailure);
        }
    }

    private void updateRssi(int rssiValue) {
        Log.i(TAG, "rssi - " + rssiValue);
    }

    private void handleBleScanException(BleScanException bleScanException) {
        final String text;

        switch (bleScanException.getReason()) {
            case BleScanException.BLUETOOTH_NOT_AVAILABLE:
                text = "Bluetooth is not available";
                break;
            case BleScanException.BLUETOOTH_DISABLED:
                text = "Enable bluetooth and try again";
                break;
            case BleScanException.LOCATION_PERMISSION_MISSING:
                text = "On Android 6.0 location permission is required. Implement Runtime Permissions";
                break;
            case BleScanException.LOCATION_SERVICES_DISABLED:
                text = "Location services needs to be enabled on Android 6.0";
                break;
            case BleScanException.SCAN_FAILED_ALREADY_STARTED:
                text = "Scan with the same filters is already started";
                break;
            case BleScanException.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                text = "Failed to register application for bluetooth scan";
                break;
            case BleScanException.SCAN_FAILED_FEATURE_UNSUPPORTED:
                text = "Scan with specified parameters is not supported";
                break;
            case BleScanException.SCAN_FAILED_INTERNAL_ERROR:
                text = "Scan failed due to internal error";
                break;
            case BleScanException.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:
                text = "Scan cannot start due to limited hardware resources";
                break;
            case BleScanException.UNDOCUMENTED_SCAN_THROTTLE:
                text = String.format(
                        Locale.getDefault(),
                        "Android 7+ does not allow more scans. Try in %d seconds",
                        secondsTill(bleScanException.getRetryDateSuggestion())
                );
                break;
            case BleScanException.UNKNOWN_ERROR_CODE:
            case BleScanException.BLUETOOTH_CANNOT_START:
            default:
                text = "Unable to start scanning";
                break;
        }
        Log.w("EXCEPTION", text, bleScanException);

        Snackbar.make(findViewById(R.id.main), "Scan error : " + text, Snackbar.LENGTH_SHORT).show();
    }

    private long secondsTill(Date retryDateSuggestion) {
        return MILLISECONDS.toSeconds(retryDateSuggestion.getTime() - System.currentTimeMillis());
    }

    private void onScanFailure(Throwable throwable) {
        if (throwable instanceof BleScanException) {
            handleBleScanException((BleScanException) throwable);
        }
    }

    private void onConnectionFailure(Throwable throwable) {
        //noinspection ConstantConditions
        Snackbar.make(findViewById(R.id.main), "Connection error: " + throwable, Snackbar.LENGTH_SHORT).show();
    }

    @SuppressWarnings("unused")
    private void onConnectionReceived(RxBleConnection connection) {
        // Snackbar.make(findViewById(R.id.main), "Connection received", Snackbar.LENGTH_SHORT).show();
    }

    private void onConnectionStateChange(RxBleConnection.RxBleConnectionState newState) {
        if (newState == RxBleConnection.RxBleConnectionState.DISCONNECTED) {
            graph.getViewport().setScrollable(true);
            graph.getViewport().setScrollableY(true);
            graph.getViewport().setScalable(true);
        }

        if (newState == RxBleConnection.RxBleConnectionState.CONNECTED) {
            graph.getViewport().setScrollable(true);
            graph.getViewport().setScrollableY(false);
            graph.getViewport().setScalable(true);
            graph.getViewport().setScalableY(false);
            graph.getViewport().setXAxisBoundsManual(true);
            graph.getViewport().setMinX(0);
            graph.getViewport().setMaxX(80);
        }
    }

    private void triggerDisconnect() {
        disconnectTriggerSubject.onNext(true);
    }

    private boolean isScanning() {
        return scanDisposable != null;
    }

    private boolean isConnected() {
        if (bleDevice != null)
            if (bleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED)
                return true;

        return false;
    }

    private void dispose() {
        scanDisposable = null;
    }

    private void notificationHasBeenSetUp() {
        Snackbar.make(findViewById(R.id.main), "Notifications has been set up", Snackbar.LENGTH_SHORT).show();
    }

    private void onNotificationReceived(byte[] bytes) {
        if (bytes != null && bytes.length == 19) {

            final String bytesToHexString = BluetoothHelper.bytesToHexString(new byte[] { bytes[4] });
            final String wind = BluetoothHelper.getWind(bytes, 5, 6);

            if (bytesToHexString.equals("37")) {
                textView_wind.setText(wind.trim());
                graphLastXValue += 1d;
                mSeriesWind.appendData(new DataPoint(graphLastXValue, Double.parseDouble(wind.trim())), true, 1000);
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
//                        try  {
//                            org.influxdb.InfluxDB influxDB = org.influxdb.InfluxDBFactory.connect("http://ec2-52-78-210-34.ap-northeast-2.compute.amazonaws.com:8086", "doctor", "doctor7");
//                            String dbName = "idoctor";
//                            DecimalFormat df = new DecimalFormat("#.00");
//                            org.influxdb.dto.Point point1 = org.influxdb.dto.Point.measurement("BREATHCARE")
//                                    .time(System.currentTimeMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
//                                    .addField("wind", df.format(Float.parseFloat(wind.trim())))
//                                    .addField("temp", df.format(Float.parseFloat(graphLastTemp.trim())))
//                                    .build();
//                            influxDB.write(dbName, "autogen", point1);
//
//                            influxDB.close();
//                        } catch (Exception e) {
//                            e.printStackTrace();
//                        }
                    }
                });
                thread.start();
            }

            if (bytesToHexString.equals("30")) {
                textView_temp.setText(wind.trim());
                mSeriesTemp.appendData(new DataPoint(graphLastXValue, Double.parseDouble(wind.trim())), true, 1000);
                graphLastTemp = wind;
            }
        } else {
            // Log.d(TAG, "Not Found (" + value.length + ")");
        }
    }

    private void onNotificationSetupFailure(Throwable throwable) {
        Log.i(TAG, "Notifications error: " + throwable.getMessage(), throwable);
        triggerDisconnect();
        Snackbar.make(findViewById(R.id.main), "Notifications error: " + throwable, Snackbar.LENGTH_SHORT).show();

    }

    private void onWriteSuccess() {

    }

    private void onWriteFailure(Throwable throwable) {
        Log.i(TAG, "Write error: " + throwable.getMessage(), throwable);
        triggerDisconnect();
        Snackbar.make(findViewById(R.id.main), "Write error: " + throwable, Snackbar.LENGTH_SHORT).show();
    }

}
