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

package app.io18watchface;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.OnCompositionLoadedListener;

import java.util.Calendar;

public class LayoutFaceService extends CanvasWatchFaceService {

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        static final int MSG_UPDATE_TIME = 0;

        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler updateTimeHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        updateDigits(-1);
                        updateTimer();
                        break;
                }
                
                return false;
            }
        });

        final BroadcastReceiver timeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateDigits(0);
            }
        };
        
        private LottieComposition[] composedAnimations = new LottieComposition[10];
        private int nLoaded = 0;

        private boolean isZoneReceiverRegistered = false;
        private View layoutRoot;
        private LottieAnimationView[] digitViews = new LottieAnimationView[6];
        private final ValueAnimator outAnimator = ValueAnimator.ofFloat(0.5f, 1f);
        private int hours, minutes, seconds, firstDigit, lastDigit;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            // Preload the Lottie animations from assets
            for (int i = 0; i < 10; i++) {
                final int finalI = i;
                LottieComposition.Factory.fromAssetFileName(LayoutFaceService.this, i + ".json",
                        new OnCompositionLoadedListener() {
                            @Override
                            public void onCompositionLoaded(@Nullable LottieComposition composition) {
                                composedAnimations[finalI] = composition;
                                nLoaded += 1;
                                if ((nLoaded == composedAnimations.length) && isInAmbientMode()) {
                                    updateDigits(0);
                                }
                            }
                        });
            }

            // Inflate the layout that we're using for the watch face
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            layoutRoot = inflater.inflate(R.layout.linear_main, null);

            // Preload pointers to the digit views for later use
            digitViews[0] = layoutRoot.findViewById(R.id.hourTens);
            digitViews[1] = layoutRoot.findViewById(R.id.hourOnes);
            digitViews[2] = layoutRoot.findViewById(R.id.minuteTens);
            digitViews[3] = layoutRoot.findViewById(R.id.minuteOnes);
            digitViews[4] = layoutRoot.findViewById(R.id.secondTens);
            digitViews[5] = layoutRoot.findViewById(R.id.secondOnes);

            for (LottieAnimationView digitView : digitViews) {
                // Set up Lottie for each digit
                digitView.useHardwareAcceleration();
                digitView.addAnimatorListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        invalidate();
                    }
                });
                digitView.addAnimatorUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator valueAnimator) {
                        postInvalidate();
                    }
                });
            }

            // Set up an animator used to animate all currently-changing digits out of view. I use an
            // external animator (rather than Lottie's built-in support) for this so that they 
            // happen in unison and invoke fewer calls to postInvalidate().
            outAnimator.setDuration(500);
            outAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator valueAnimator) {
                    for (int i = firstDigit; i <= lastDigit; i++) {
                        digitViews[i].setProgress((Float) valueAnimator.getAnimatedValue());
                    }
                    invalidate();
                }
            });
            outAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // When this animation finishes, immediately start animating the new digits into 
                    // view. I'd prefer to use an external animator here too, but that caused 
                    // problems, so I'm going with Lottie's built-in animation support instead.
                    setDigits();
                    for (int i = firstDigit; i <= lastDigit; i++) {
                        digitViews[i].setMinAndMaxProgress(0f, 0.5f);
                        digitViews[i].playAnimation();
                    }
                }
            });
        }

        @Override
        public void onDestroy() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                updateDigits(0);
                registerZoneReceiver();
            } else {
                unregisterZoneReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerZoneReceiver() {
            if (isZoneReceiverRegistered) {
                return;
            }
            isZoneReceiverRegistered = true;
            LayoutFaceService.this.registerReceiver(timeZoneReceiver, 
                    new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED));
        }

        private void unregisterZoneReceiver() {
            if (!isZoneReceiverRegistered) {
                return;
            }
            LayoutFaceService.this.unregisterReceiver(timeZoneReceiver);
            isZoneReceiverRegistered = false;
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            int gravity = !insets.isRound() ? (Gravity.START | Gravity.TOP) :  Gravity.CENTER;
            setWatchFaceStyle(new WatchFaceStyle.Builder(LayoutFaceService.this)
                    .setStatusBarGravity(gravity)
                    .setAccentColor(0xff526cfe)
                    .build());

            // Load the display spec - we'll need this later for measuring layoutRoot
            Point displaySize = new Point();
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay()
                    .getSize(displaySize);

            if (layoutRoot instanceof LinearLayout) {
                int height = (int) (displaySize.y * 0.28);
                int width = (int) (height * 0.62);
                for (LottieAnimationView digitView : digitViews) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) digitView.getLayoutParams();
                    params.width = width;
                    params.height = height;
                }
                //noinspection SuspiciousNameCombination
                layoutRoot.findViewById(R.id.logo).setLayoutParams(new LinearLayout.LayoutParams(height, height));
            }
            
            // Recompute the MeasureSpec fields - these determine the actual size of the layout
            int specW = View.MeasureSpec.makeMeasureSpec(displaySize.x, View.MeasureSpec.EXACTLY);
            int specH = View.MeasureSpec.makeMeasureSpec(displaySize.y, View.MeasureSpec.EXACTLY);

            // Update the layout
            layoutRoot.measure(specW, specH);
            layoutRoot.layout(0, 0, layoutRoot.getMeasuredWidth(), layoutRoot.getMeasuredHeight());
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            updateDigits(-1);
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            // Show/hide the logo and seconds fields
            if (inAmbientMode) {
                layoutRoot.findViewById(R.id.logo).setVisibility(View.INVISIBLE);
                digitViews[4].setVisibility(View.INVISIBLE);
                digitViews[5].setVisibility(View.INVISIBLE);
                postInvalidate();
            } else {
                layoutRoot.findViewById(R.id.logo).setVisibility(View.VISIBLE);
                digitViews[4].setVisibility(View.VISIBLE);
                digitViews[5].setVisibility(View.VISIBLE);
                updateDigits(4);
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            canvas.drawColor(Color.BLACK);
            // Draw the layout to the Canvas
            layoutRoot.draw(canvas);
        }

        /**
         * Stops the {@link #updateTimeHandler} timer, then retsarts if it should be running.
         */
        private void updateTimer() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            
            if (isVisible() && !isInAmbientMode()) {
                final long delayMs = DateUtils.SECOND_IN_MILLIS -
                        (System.currentTimeMillis() % DateUtils.SECOND_IN_MILLIS);
                updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        /**
         * Assign animations to each digit based on the current time fields
         */
        private void setDigits() {
            digitViews[0].setComposition(composedAnimations[hours / 10]);
            digitViews[1].setComposition(composedAnimations[hours % 10]);
            digitViews[2].setComposition(composedAnimations[minutes / 10]);
            digitViews[3].setComposition(composedAnimations[minutes % 10]);
            digitViews[4].setComposition(composedAnimations[seconds / 10]);
            digitViews[5].setComposition(composedAnimations[seconds % 10]);
        }

        /**
         * Generates digits for the current time, and invokes onDraw
         * 
         * @param regenDigit If non-negative, only regenerate digits starting at this index; otherwise,
         *                   the code attempts to figure put which digits need regenerating (which
         *                   avoids animating digits that aren't currently changing). For example, 
         *                   a value of 0 here means regenerate all digits unconditionally; a value 
         *                   of 4 means regen the "seconds" digits (indices 4 & 5).
         */
        private void updateDigits(int regenDigit) {
            // Get the current time and split it into fields
            Calendar now = Calendar.getInstance();
            hours = now.get(Calendar.HOUR_OF_DAY);
            minutes = now.get(Calendar.MINUTE);
            seconds = now.get(Calendar.SECOND);

            // Figure out bounds for the digit indices to regenerate
            if (regenDigit >= 0) {
                firstDigit = regenDigit;
            } else {
                if (seconds > 0) {
                    firstDigit = (seconds % 10 == 0) ? 4 : 5;
                } else if (minutes > 0) {
                    firstDigit = (minutes % 10 == 0) ? 2 : 3;
                } else {
                    firstDigit = (hours % 10 == 0) ? 0 : 1;
                }
            }
            lastDigit = isInAmbientMode() ? 3 : (regenDigit == 0) ? 4 : 5;

            if (isInAmbientMode()) {
                Log.d("FaceService", "updateDigits inAmbient");
                // No animation in ambient mode
                setDigits();
                for (LottieAnimationView digit : digitViews) {
                    digit.setProgress(0.5f);
                }
                postInvalidate();

            } else if (regenDigit >= 0) {
                // Animate digits into view
                setDigits();
                for (int i = firstDigit; i <= lastDigit; i++) {
                    digitViews[i].setMinAndMaxProgress(0f, 0.5f);
                    digitViews[i].setProgress(0f);
                    digitViews[i].playAnimation();
                }

            } else {
                // Normal "tick": start by animating the old digits out of view
                for (int i = firstDigit; i <= lastDigit; i++) {
                    digitViews[i].setMinAndMaxProgress(0.5f, 1f);
                }
                outAnimator.start();
            }
        }
    }
}
