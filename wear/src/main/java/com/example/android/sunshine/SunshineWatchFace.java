/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v7.graphics.Palette;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String TAG = "SunshineWatchFace";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);


    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }


    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private static final float HOUR_STROKE_WIDTH = 5f;
        private static final float MINUTE_STROKE_WIDTH = 5f;
        private static final float SECOND_TICK_STROKE_WIDTH = 3f;
        private static final float CENTER_GAP_AND_CIRCLE_RADIUS = 4f;
        private static final int SHADOW_RADIUS = 6;

        private static final int AMBIENT_DATE = 0;
        private static final int AMBIENT_WEATHER_ART = 2;
        private static final int AMBIENT_TEMPERATURE = 1;


        private SimpleDateFormat dayOfWeek = new SimpleDateFormat("EEE", Locale.getDefault());
        private SimpleDateFormat dateToday = new SimpleDateFormat("MMM dd", Locale.getDefault());

        /* Set defaults for colors */
        private int mWatchHandColor = Color.WHITE;
        private int mWatchHandHighlightColor = Color.YELLOW;
        private int mWatchHandShadowColor = Color.BLACK;
        private int mDateColor;

        private float mCenterX;
        private float mCenterY;

        private float mSecondHandLength;
        private float sMinuteHandLength;
        private float sHourHandLength;

        private Paint mHourPaint;
        private Paint mMinutePaint;
        private Paint mSecondPaint;
        private Paint mTickAndCirclePaint;
        private Paint mDatePaint;
        private Paint mDayPaint;
        private Paint mHighPaint;
        private Paint mHighTextPaint;
        private Paint mLowPaint;
        private Paint mLowTextPaint;
        private Paint grayPaint;

        private int weatherId;
        private double highTemp;
        private double lowTemp;

        private Bitmap mBackgroundBitmap;
        private Bitmap mGrayBackgroundBitmap;
        private Bitmap weatherBitmap;
        private boolean mBurnInProtection;
        private int toggler;

        private Rect mPeekCardBounds = new Rect();
        private GoogleApiClient mGoogleApiClient;

        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {

                invalidate();
                if (shouldTimerBeRunning()) {
                    long timeMs = System.currentTimeMillis();
                    long delayMs = INTERACTIVE_UPDATE_RATE_MS
                            - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                    mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                }

            }
        };

        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        boolean mAmbient;
        Calendar mCalendar;
        Date mDate;


        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mXDateOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_PERSISTENT)
                    .setShowSystemUiTime(false)
                    .setStatusBarGravity(Gravity.TOP | Gravity.END)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR | WatchFaceStyle.PROTECT_HOTWORD_INDICATOR)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.blue));
            mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg);
            mDateColor = ContextCompat.getColor(getApplicationContext(), R.color.date_color);


            // Weather art
            if (weatherId > 0) {
                weatherBitmap = BitmapFactory.decodeResource(getResources(), Util.getArtResource(weatherId));
                if (weatherBitmap != null)
                    weatherBitmap = Bitmap.createScaledBitmap(weatherBitmap, weatherBitmap.getWidth() / 2, weatherBitmap.getHeight() / 2, true);
            }

            mHourPaint = getHourHandPaint();
            mMinutePaint = getMinuteHandPaint();
            mSecondPaint = getSecondHandPaint();
            mTickAndCirclePaint = getTickAndCirclePaint();


            mDatePaint = createTextPaint(12, BOLD_TYPEFACE, mDateColor);
            mDayPaint = createTextPaint(16, BOLD_TYPEFACE, mDateColor);

            mHighPaint = createTextPaint(20, BOLD_TYPEFACE, Color.YELLOW);
            mLowPaint = createTextPaint(20, BOLD_TYPEFACE, Color.YELLOW);

            mHighTextPaint = createTextPaint(16, NORMAL_TYPEFACE, Color.WHITE);
            mLowTextPaint = createTextPaint(16, NORMAL_TYPEFACE, Color.WHITE);

            grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);

            Palette.PaletteAsyncListener paletteListener = new Palette.PaletteAsyncListener() {
                @Override
                public void onGenerated(Palette palette) {
                    if (palette != null) {

                        mWatchHandHighlightColor = palette.getVibrantColor(Color.YELLOW);
                        mWatchHandColor = palette.getVibrantColor(Color.WHITE);
                        mWatchHandShadowColor = palette.getDarkMutedColor(Color.BLACK);
                        mDateColor = palette.getVibrantColor(ContextCompat.getColor(getApplicationContext(), R.color.date_color));
                        updateWatchHandStyle();
                    }
                }
            };

            Palette.from(mBackgroundBitmap).generate(paletteListener);
            mCalendar = Calendar.getInstance();
            mDate = mCalendar.getTime();
        }

        private void updateWatchHandStyle() {
            if (mAmbient) {
                mHourPaint.setColor(Color.WHITE);
                mHourPaint.setAntiAlias(false);
                mHourPaint.clearShadowLayer();

                mMinutePaint.setColor(Color.WHITE);
                mMinutePaint.setAntiAlias(false);
                mMinutePaint.clearShadowLayer();

                mHighPaint.setColor(Color.WHITE);
                mHighPaint.setAntiAlias(false);
                mHighPaint.clearShadowLayer();

                mLowPaint.setColor(Color.WHITE);
                mLowPaint.setAntiAlias(false);
                mLowPaint.clearShadowLayer();

            } else {

                mHourPaint.setColor(mWatchHandColor);
                mMinutePaint.setColor(mWatchHandColor);
                mSecondPaint.setColor(mWatchHandHighlightColor);
                mTickAndCirclePaint.setColor(mWatchHandColor);

                mHourPaint.setAntiAlias(true);
                mMinutePaint.setAntiAlias(true);
                mSecondPaint.setAntiAlias(true);
                mTickAndCirclePaint.setAntiAlias(true);

                mHourPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mMinutePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mSecondPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mTickAndCirclePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

                mHighPaint.setColor(Color.YELLOW);
                mHighPaint.setAntiAlias(true);
                mHighPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

                mLowPaint.setColor(Color.YELLOW);
                mLowPaint.setAntiAlias(true);
                mLowPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }


        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected())
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            mPeekCardBounds.set(rect);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.x_offset_round : R.dimen.x_offset);
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.y_offset_round : R.dimen.y_offset);
            mXDateOffset = resources.getDimension(isRound
                    ? R.dimen.x_date_offset_round : R.dimen.x_date_offset);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            mAmbient = inAmbientMode;
            updateWatchHandStyle();
            invalidate();


            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            if (mAmbient) dateWeatherToggler();
            mock();
            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK);
            } else if (mAmbient) {
                canvas.drawColor(ContextCompat.getColor(getApplicationContext(), R.color.background));
            } else {
                canvas.drawColor(ContextCompat.getColor(getApplicationContext(), R.color.background));
            }

            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);

            String day = dayOfWeek.format(mCalendar.getTime()).toUpperCase();
            String date = dateToday.format(mCalendar.getTime()).toUpperCase();


            // max and min
            if (highTemp > 0.0 && lowTemp > 0.0 && (!mAmbient || toggler == AMBIENT_TEMPERATURE)) {
                String high = String.valueOf(Math.round(highTemp)) + "°";
                String low = String.valueOf(Math.round(lowTemp)) + "°";

                canvas.drawText(high, mCenterX - (mCenterX / 2) - mXOffset, mCenterY, mHighPaint);
                canvas.drawText("max", mCenterX - (mCenterX / 2) - mXOffset - 4, mCenterY + mHighPaint.getTextSize(), mHighTextPaint);
                canvas.drawText(low, mCenterX + (mCenterX / 2), mCenterY, mLowPaint);
                canvas.drawText("min", mCenterX + (mCenterX / 2), mCenterY + mLowPaint.getTextSize(), mLowTextPaint);
            }

            // Weather art
            if (weatherBitmap == null && weatherId > 0) {

                weatherBitmap = BitmapFactory.decodeResource(getResources(), Util.getArtResource(weatherId));
                if (weatherBitmap != null)
                    weatherBitmap = Bitmap.createScaledBitmap(weatherBitmap, weatherBitmap.getWidth() / 2, weatherBitmap.getHeight() / 2, true);
            }

            if (weatherBitmap != null && (!mAmbient || toggler == AMBIENT_WEATHER_ART)) {
                Paint filter = null;
                if (mAmbient)
                    filter = grayPaint;
                canvas.drawBitmap(weatherBitmap, mCenterX - weatherBitmap.getWidth() / 2, mCenterY - (weatherBitmap.getHeight()) - mYOffset, filter);

            }

            // Date
            if (!mAmbient || toggler == AMBIENT_DATE) {
                canvas.drawText(day, mCenterX - (mDayPaint.getTextSize()), mCenterY + (mCenterY / 4) + 2 * mYOffset, mDayPaint);
                canvas.drawText(date, mCenterX - (mDatePaint.getTextSize()) - mXDateOffset, mCenterY + (mCenterY / 4) + 2 * mYOffset + mDayPaint.getTextSize(), mDatePaint);
            }
            //+y moves to bottom
            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */
            if (!mAmbient) {
                float innerTickRadius = mCenterX - 20;
                float outerTickRadius = mCenterX - 22;
                for (int tickIndex = 0; tickIndex < seconds; tickIndex++) {
                    float tickRot = (float) (tickIndex * Math.PI * 2 / 60);
                    float innerX = (float) Math.sin(tickRot) * innerTickRadius;
                    float innerY = (float) -Math.cos(tickRot) * innerTickRadius;
                    float outerX = (float) Math.sin(tickRot) * outerTickRadius;
                    float outerY = (float) -Math.cos(tickRot) * outerTickRadius;
                    canvas.drawLine(mCenterX + innerX, mCenterY + innerY,
                            mCenterX + outerX, mCenterY + outerY, mTickAndCirclePaint);
                }
            }
            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float secondsRotation = seconds * 6f;

            final float minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;

            /*
             * Save the canvas state before we can begin to rotate it.
             */
            canvas.save();

            canvas.rotate(hoursRotation, mCenterX, mCenterY);
            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - sHourHandLength,
                    mHourPaint);

            canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY);
            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - sMinuteHandLength,
                    mMinutePaint);

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            if (!mAmbient) {
                canvas.rotate(secondsRotation - minutesRotation, mCenterX, mCenterY);
                canvas.drawLine(
                        mCenterX,
                        mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                        mCenterX,
                        mCenterY - mSecondHandLength,
                        mSecondPaint);

            }
            canvas.drawCircle(
                    mCenterX,
                    mCenterY,
                    CENTER_GAP_AND_CIRCLE_RADIUS,
                    mTickAndCirclePaint);

            /* Restore the canvas' original orientation. */
            canvas.restore();

            /* Draw rectangle behind peek card in ambient mode to improve readability. */
            if (mAmbient) {
                canvas.drawRect(mPeekCardBounds, mBackgroundPaint);
            }
        }

        private void mock() {
            if (weatherId == 0) {
                weatherId = 800;
                highTemp = 25;
                lowTemp = 14;
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f;
            mCenterY = height / 2f;

            /*
             * Calculate lengths of different hands based on watch screen size.
             */
            mSecondHandLength = (float) (mCenterX * 0.875);
            sMinuteHandLength = (float) (mCenterX * 0.75);
            sHourHandLength = (float) (mCenterX * 0.5);


            /* Scale loaded background image (more efficient) if surface dimensions change. */
            float scale = ((float) width) / (float) mBackgroundBitmap.getWidth();

            mBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                    (int) (mBackgroundBitmap.getWidth() * scale),
                    (int) (mBackgroundBitmap.getHeight() * scale), true);

            /*
             * Create a gray version of the image only if it will look nice on the device in
             * ambient mode. That means we don't want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren't
             * selecting their own photos for the watch face), it will be more
             * efficient to create a black/white version (png, etc.) and load that when you need it.
             */
            if (!mBurnInProtection && !mLowBitAmbient) {
                initGrayBackgroundBitmap();
            }
        }

        private void initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createBitmap(
                    mBackgroundBitmap.getWidth(),
                    mBackgroundBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mGrayBackgroundBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mBackgroundBitmap, 0, 0, grayPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }


        private Paint getHourHandPaint() {
            return getPaint(mWatchHandColor, HOUR_STROKE_WIDTH);
        }

        private Paint getMinuteHandPaint() {
            return getPaint(mWatchHandColor, MINUTE_STROKE_WIDTH);
        }

        private Paint getSecondHandPaint() {
            return getPaint(mWatchHandHighlightColor, SECOND_TICK_STROKE_WIDTH);
        }

        private Paint getTickAndCirclePaint() {
            return getPaint(mWatchHandColor, SECOND_TICK_STROKE_WIDTH);
        }

        private Paint createTextPaint(int size, Typeface typeface, @ColorInt int color) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setTypeface(typeface);
            paint.setTextSize(size);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint getPaint(int color, float strokeWidth) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setStrokeWidth(strokeWidth);
            paint.setAntiAlias(true);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setShadowLayer(SHADOW_RADIUS, 0, 0, color);
            return paint;
        }

        private int dateWeatherToggler() {
            toggler++;
            // put count to 3 to enable weather art
            if (toggler >= 2)
                toggler = 0;

            if (weatherId <= 0)
                toggler = AMBIENT_DATE;

            return toggler;
        }


        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            syncData();

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.i(TAG, "New data detected");

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    if ("/forecast".compareTo(event.getDataItem().getUri().getPath()) == 0) {
                        DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                        updateUi(map);
                    }
                }
            }
        }

        private void syncData() {

            Log.i(TAG, "Syncing data from handheld");

            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                @Override
                public void onResult(NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                    for (Node node : getConnectedNodesResult.getNodes()) {
                        final String localNode = node.getId();
                        Uri uri = new Uri.Builder()
                                .scheme(PutDataRequest.WEAR_URI_SCHEME)
                                .path("/forecast")
                                .authority(localNode)
                                .build();
                        Wearable.DataApi.getDataItem(mGoogleApiClient, uri)
                                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                                    @Override
                                    public void onResult(DataApi.DataItemResult dataItemResult) {
                                        if (dataItemResult.getStatus().isSuccess() && dataItemResult.getDataItem() != null) {
                                            updateUi(DataMapItem.fromDataItem(dataItemResult.getDataItem()).getDataMap());
                                        }

                                    }
                                });
                    }
                }
            });

        }

        private void updateUi(DataMap dataSet) {
            Log.i(TAG, "Updating UI with new values");
            weatherId = dataSet.getInt("weather_id");
            highTemp = dataSet.getDouble("max");
            lowTemp = dataSet.getDouble("min");
            saveDataSet(dataSet);
            weatherBitmap = null;
            invalidate();
        }

        private void saveDataSet(DataMap dataSet) {
            PutDataMapRequest request = PutDataMapRequest.create("/forecast");
            DataMap newDataMap = request.getDataMap();
            newDataMap.putAll(dataSet);
            Wearable.DataApi.putDataItem(mGoogleApiClient, request.asPutDataRequest());
        }

    }

}
