package com.example.android.sunshine.app.sync;

import android.app.IntentService;
import android.content.Intent;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p>
 * helper methods.
 */
public class WearableUpdaterService extends IntentService implements GoogleApiClient.OnConnectionFailedListener {

    private static final String[] FORECAST_COLUMNS = new String[]{
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP
    };

    private GoogleApiClient mGoogleApiClient;

    public WearableUpdaterService() {
        super("WearableUpdaterService");
    }

    /**
     * Starts this service to perform action Foo with the given parameters. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */

    public static void updateWatchFace(Context context) {
        Log.wtf("DataMap", "starting watch face update service");
        Intent intent = new Intent(context, WearableUpdaterService.class);
        intent.setAction(SunshineSyncAdapter.ACTION_DATA_UPDATED);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if (SunshineSyncAdapter.ACTION_DATA_UPDATED.equalsIgnoreCase(action)) {
                Log.wtf("DataMap", "stared watch face update service");

                String location = Utility.getPreferredLocation(getApplicationContext());
                Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(location, System.currentTimeMillis());
                Cursor cursor = getContentResolver().query(weatherUri, FORECAST_COLUMNS, null, null, WeatherContract.WeatherEntry.COLUMN_DATE + " ASC");
                if (cursor.moveToFirst()) {
                    int weatherId = cursor.getInt(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID));
                    double high = cursor.getDouble(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP));
                    double low = cursor.getDouble(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP));

                    updateWatchFace(weatherId, high, low);

                }
                cursor.close();
            }
        }
    }

    /**
     * Handle action DATA UPDATED in the provided background thread with the forecast
     * parameters.
     */
    private void updateWatchFace(final int weatherId, final double high, final double low) {
        Log.wtf("DataMap", "updating watch face datamap, waiting for connection");

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                        Log.wtf("DataMap", "Watch face connected to google api, updating data");

                        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/forecast");
                        DataMap dataMap = putDataMapRequest.getDataMap();
                        dataMap.putInt(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID, weatherId);
                        dataMap.putDouble(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP, high);
                        dataMap.putDouble(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP, low);
                        dataMap.putLong("update", System.currentTimeMillis());
                        putDataMapRequest.setUrgent();

                        PutDataRequest putDataRequest = putDataMapRequest.asPutDataRequest();

                        PendingResult<DataApi.DataItemResult> pendingResult =
                                Wearable.DataApi.putDataItem(mGoogleApiClient, putDataRequest.setUrgent());


                    }

                    @Override
                    public void onConnectionSuspended(int cause) {
                        Log.wtf("DataMap", "watch face update service onConnectionSuspended");

                    }
                })
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.wtf("DataMap", "watch face update service onConnectionFailed");

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

}
