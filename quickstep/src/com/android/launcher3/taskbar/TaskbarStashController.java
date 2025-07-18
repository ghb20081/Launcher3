/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.launcher3.taskbar;

import static android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS;

import static com.android.app.animation.Interpolators.EMPHASIZED;
import static com.android.app.animation.Interpolators.FINAL_FRAME;
import static com.android.app.animation.Interpolators.INSTANT;
import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.internal.jank.InteractionJankMonitor.Configuration;
import static com.android.launcher3.Flags.enableScalingRevealHomeAnimation;
import static com.android.launcher3.Flags.syncAppLaunchWithTaskbarStash;
import static com.android.launcher3.QuickstepTransitionManager.PINNED_TASKBAR_TRANSITION_DURATION;
import static com.android.launcher3.config.FeatureFlags.ENABLE_TASKBAR_NAVBAR_UNIFICATION;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TRANSIENT_TASKBAR_HIDE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TRANSIENT_TASKBAR_SHOW;
import static com.android.launcher3.taskbar.TaskbarActivityContext.ENABLE_TASKBAR_BEHIND_SHADE;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.FlagDebugUtils.appendFlag;
import static com.android.launcher3.util.FlagDebugUtils.formatFlagChange;
import static com.android.quickstep.util.SystemActionConstants.SYSTEM_ACTION_ID_TASKBAR;
import static com.android.quickstep.util.SystemUiFlagUtils.isTaskbarHidden;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BUBBLES_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DIALOG_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_VISIBLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.RemoteAction;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import android.util.Log;
import android.view.InsetsController;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Interpolator;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.launcher3.Alarm;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.AnimatorListeners;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.util.SystemUiFlagUtils;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.StringJoiner;
import java.util.function.LongPredicate;

/**
 * Coordinates between controllers such as TaskbarViewController and StashedHandleViewController to
 * create a cohesive animation between stashed/unstashed states.
 */
public class TaskbarStashController implements TaskbarControllers.LoggableTaskbarController {
    private static final String TAG = "TaskbarStashController";
    private static final boolean DEBUG = false;

    /**
     * Def. value for @param shouldBubblesFollow in
     * {@link #updateAndAnimateTransientTaskbar(boolean)} */
    public static boolean SHOULD_BUBBLES_FOLLOW_DEFAULT_VALUE = true;

    public static final int FLAG_IN_APP = 1 << 0;
    public static final int FLAG_STASHED_IN_APP_SYSUI = 1 << 1; // shade open, ...
    public static final int FLAG_STASHED_IN_APP_SETUP = 1 << 2; // setup wizard and AllSetActivity
    public static final int FLAG_STASHED_IME = 1 << 3; // IME is visible
    public static final int FLAG_IN_STASHED_LAUNCHER_STATE = 1 << 4;
    public static final int FLAG_STASHED_IN_TASKBAR_ALL_APPS = 1 << 5; // All apps is visible.
    public static final int FLAG_IN_SETUP = 1 << 6; // In the Setup Wizard
    public static final int FLAG_STASHED_SMALL_SCREEN = 1 << 7; // phone screen gesture nav, stashed
    public static final int FLAG_STASHED_IN_APP_AUTO = 1 << 8; // Autohide (transient taskbar).
    public static final int FLAG_STASHED_SYSUI = 1 << 9; //  app pinning,...
    public static final int FLAG_STASHED_DEVICE_LOCKED = 1 << 10; // device is locked: keyguard, ...
    public static final int FLAG_IN_OVERVIEW = 1 << 11; // launcher is in overview
    // An internal no-op flag to determine whether we should delay the taskbar background animation
    private static final int FLAG_DELAY_TASKBAR_BG_TAG = 1 << 12;
    public static final int FLAG_STASHED_FOR_BUBBLES = 1 << 13; // show handle for stashed hotseat
    public static final int FLAG_TASKBAR_HIDDEN = 1 << 14; // taskbar hidden during dream, etc...
    // taskbar should always be stashed for bubble bar on phone
    public static final int FLAG_STASHED_BUBBLE_BAR_ON_PHONE = 1 << 15;

    public static final int FLAG_IGNORE_IN_APP = 1 << 16; // used to sync with app launch animation

    // If any of these flags are enabled, isInApp should return true.
    private static final int FLAGS_IN_APP = FLAG_IN_APP | FLAG_IN_SETUP;

    // If we're in an app and any of these flags are enabled, taskbar should be stashed.
    private static final int FLAGS_STASHED_IN_APP = FLAG_STASHED_IN_APP_SYSUI
            | FLAG_STASHED_IN_APP_SETUP | FLAG_STASHED_IN_TASKBAR_ALL_APPS
            | FLAG_STASHED_SMALL_SCREEN | FLAG_STASHED_IN_APP_AUTO | FLAG_STASHED_IME;

    // If we're in overview and any of these flags are enabled, taskbar should be stashed.
    private static final int FLAGS_STASHED_IN_OVERVIEW = FLAG_STASHED_IME;

    // If any of these flags are enabled, inset apps by our stashed height instead of our unstashed
    // height. This way the reported insets are consistent even during transitions out of the app.
    // Currently any flag that causes us to stash in an app is included, except for IME or All Apps
    // since those cover the underlying app anyway and thus the app shouldn't change insets.
    private static final int FLAGS_REPORT_STASHED_INSETS_TO_APP = FLAGS_STASHED_IN_APP
            & ~FLAG_STASHED_IME & ~FLAG_STASHED_IN_TASKBAR_ALL_APPS & ~FLAG_STASHED_IN_APP_SYSUI;

    // If any of these flags are enabled, the taskbar must be stashed.
    private static final int FLAGS_FORCE_STASHED = FLAG_STASHED_SYSUI | FLAG_STASHED_DEVICE_LOCKED
            | FLAG_STASHED_IN_TASKBAR_ALL_APPS | FLAG_STASHED_SMALL_SCREEN
            | FLAG_STASHED_FOR_BUBBLES | FLAG_STASHED_BUBBLE_BAR_ON_PHONE;

    /**
     * How long to stash/unstash when manually invoked via long press.
     *
     * Use {@link #getStashDuration()} to query duration
     */
    @VisibleForTesting
    static final long TASKBAR_STASH_DURATION = InsetsController.ANIMATION_DURATION_RESIZE;

    /**
     * How long to stash/unstash transient taskbar.
     *
     * Use {@link #getStashDuration()} to query duration.
     */
    @VisibleForTesting
    static final long TRANSIENT_TASKBAR_STASH_DURATION = 417;

    /**
     * How long to stash/unstash when keyboard is appearing/disappearing.
     */
    @VisibleForTesting
    static final long TASKBAR_STASH_DURATION_FOR_IME = 80;

    /**
     * The scale TaskbarView animates to when being stashed.
     */
    protected static final float STASHED_TASKBAR_SCALE = 0.5f;

    /**
     * How long the hint animation plays, starting on motion down.
     */
    private static final long TASKBAR_HINT_STASH_DURATION =
            ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT;

    /**
     * How long to delay the icon/stash handle alpha.
     */
    public static final long TASKBAR_STASH_ALPHA_START_DELAY = 33;

    /**
     * How long the icon/stash handle alpha animation plays.
     */
    public static final long TRANSIENT_TASKBAR_STASH_ALPHA_DURATION = 50;

    /**
     * How long to delay the icon/stash handle alpha for the home to app taskbar animation.
     */
    private static final long TASKBAR_STASH_ICON_ALPHA_HOME_TO_APP_START_DELAY = 66;

    /**
     * The scale that the stashed handle animates to when hinting towards the unstashed state.
     */
    private static final float UNSTASHED_TASKBAR_HANDLE_HINT_SCALE = 1.1f;

    /**
     * Whether taskbar should be stashed out of the box.
     */
    private static final boolean DEFAULT_STASHED_PREF = false;

    // Auto stashes when user has not interacted with the Taskbar after X ms.
    private static final long NO_TOUCH_TIMEOUT_TO_STASH_MS = 5000;

    // Duration for which an unlock event is considered "current", as other events are received
    // asynchronously.
    public static final long UNLOCK_TRANSITION_MEMOIZATION_MS = 200;

    /**
     * The default stash animation, morphing the taskbar into the navbar.
     */
    private static final int TRANSITION_DEFAULT = 0;
    /**
     * Transitioning from launcher to app. Same as TRANSITION_DEFAULT, differs in internal
     * animation timings.
     */
    private static final int TRANSITION_HOME_TO_APP = 1;
    /**
     * Fading the navbar in and out, where the taskbar jumpcuts in and out at the very begin/end of
     * the transition. Used to transition between the hotseat and navbar` without the stash/unstash
     * transition.
     */
    private static final int TRANSITION_HANDLE_FADE = 2;
    /**
     * Same as TRANSITION_DEFAULT, but exclusively used during an "navbar unstash to hotseat
     * animation" bound to the progress of a swipe gesture. It differs from TRANSITION_DEFAULT
     * by not scaling the height of the taskbar background.
     */
    private static final int TRANSITION_UNSTASH_SUW_MANUAL = 3;

    /**
     * total duration of entering dream state animation, which we use as start delay to
     * applyState() when SYSUI_STATE_DEVICE_DREAMING flag is present. Keep this in sync with
     * DreamAnimationController.TOTAL_ANIM_DURATION.
     */
    private static final int SKIP_TOTAL_DREAM_ANIM_DURATION = 450;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            TRANSITION_DEFAULT,
            TRANSITION_HOME_TO_APP,
            TRANSITION_HANDLE_FADE,
            TRANSITION_UNSTASH_SUW_MANUAL,
    })
    private @interface StashAnimation {
    }

    private final TaskbarActivityContext mActivity;
    private final int mStashedHeight;
    private final int mUnstashedHeight;
    private final SystemUiProxy mSystemUiProxy;

    // Initialized in init.
    private TaskbarControllers mControllers;
    // Taskbar background properties.
    private AnimatedFloat mTaskbarBackgroundOffset;
    private AnimatedFloat mTaskbarImeBgAlpha;
    private MultiProperty mTaskbarBackgroundAlphaForStash;
    // TaskbarView icon properties.
    private MultiProperty mIconAlphaForStash;
    private AnimatedFloat mIconScaleForStash;
    private AnimatedFloat mIconTranslationYForStash;
    // Stashed handle properties.
    private MultiProperty mTaskbarStashedHandleAlpha;
    private AnimatedFloat mTaskbarStashedHandleHintScale;
    private final AccessibilityManager mAccessibilityManager;

    /** Whether we are currently visually stashed (might change based on launcher state). */
    private boolean mIsStashed = false;
    private long mState;

    private @Nullable AnimatorSet mAnimator;
    private boolean mIsSystemGestureInProgress;
    /** Whether the IME is visible. */
    private boolean mIsImeVisible;

    private final Alarm mTimeoutAlarm = new Alarm();
    private boolean mEnableBlockingTimeoutDuringTests = false;

    private Animator mTaskbarBackgroundAlphaAnimator;
    private final long mTaskbarBackgroundDuration;
    private boolean mUserIsNotGoingHome = false;

    private final boolean mInAppStateAffectsDesktopTasksVisibilityInTaskbar;

    // Evaluate whether the handle should be stashed
    private final LongPredicate mIsStashedPredicate = flags -> {
        boolean inApp = hasAnyFlag(flags, FLAGS_IN_APP);
        boolean stashedInApp = hasAnyFlag(flags, FLAGS_STASHED_IN_APP);
        boolean stashedLauncherState = hasAnyFlag(flags, FLAG_IN_STASHED_LAUNCHER_STATE);
        boolean inOverview = hasAnyFlag(flags, FLAG_IN_OVERVIEW);
        boolean stashedInOverview = hasAnyFlag(flags, FLAGS_STASHED_IN_OVERVIEW);
        boolean forceStashed = hasAnyFlag(flags, FLAGS_FORCE_STASHED);
        return (inApp && stashedInApp)
                || (!inApp && stashedLauncherState)
                || (inOverview && stashedInOverview)
                || forceStashed;
    };
    private final StatePropertyHolder mStatePropertyHolder = new StatePropertyHolder(
            mIsStashedPredicate);

    private boolean mIsTaskbarSystemActionRegistered = false;
    private TaskbarSharedState mTaskbarSharedState;

    public TaskbarStashController(TaskbarActivityContext activity) {
        mActivity = activity;
        mSystemUiProxy = SystemUiProxy.INSTANCE.get(activity);
        mAccessibilityManager = mActivity.getSystemService(AccessibilityManager.class);

        // Taskbar, via `TaskbarDesktopModeController`, depends on `TaskbarStashController` state to
        // determine whether desktop tasks should be shown because taskbar is pinned on the home
        // screen for freeform windowing displays. In this case, list of items shown in the taskbar
        // needs to be updated when in-app state changes.
        // TODO(b/390665752): Feature to "lock" pinned taskbar to home screen will be superseded by
        //     pinning, in other launcher states, at which point this variable can be removed.
        mInAppStateAffectsDesktopTasksVisibilityInTaskbar =
                !mActivity.showDesktopTaskbarForFreeformDisplay()
                        && mActivity.showLockedTaskbarOnHome();

        mTaskbarBackgroundDuration = activity.getResources().getInteger(
                R.integer.taskbar_background_duration);
        if (mActivity.isPhoneMode()) {
            mUnstashedHeight = mActivity.getResources().getDimensionPixelSize(
                    R.dimen.taskbar_phone_size);
            mStashedHeight = mActivity.getResources().getDimensionPixelSize(
                    R.dimen.taskbar_stashed_size);
        } else {
            mUnstashedHeight = mActivity.getDeviceProfile().taskbarHeight;
            mStashedHeight = mActivity.getDeviceProfile().stashedTaskbarHeight;
        }
    }

    /**
     * Initializes the controller
     */
    public void init(
            TaskbarControllers controllers,
            boolean setupUIVisible,
            TaskbarSharedState sharedState) {
        mControllers = controllers;
        mTaskbarSharedState = sharedState;

        TaskbarDragLayerController dragLayerController = controllers.taskbarDragLayerController;
        mTaskbarBackgroundOffset = dragLayerController.getTaskbarBackgroundOffset();
        mTaskbarImeBgAlpha = dragLayerController.getImeBgTaskbar();
        mTaskbarBackgroundAlphaForStash = dragLayerController.getBackgroundRendererAlphaForStash();

        TaskbarViewController taskbarViewController = controllers.taskbarViewController;
        mIconAlphaForStash = taskbarViewController.getTaskbarIconAlpha().get(
                TaskbarViewController.ALPHA_INDEX_STASH);
        mIconScaleForStash = taskbarViewController.getTaskbarIconScaleForStash();
        mIconTranslationYForStash = taskbarViewController.getTaskbarIconTranslationYForStash();

        StashedHandleViewController stashedHandleController =
                controllers.stashedHandleViewController;
        mTaskbarStashedHandleAlpha = stashedHandleController.getStashedHandleAlpha().get(
                StashedHandleViewController.ALPHA_INDEX_STASHED);
        mTaskbarStashedHandleHintScale = stashedHandleController.getStashedHandleHintScale();

        boolean isTransientTaskbar = mActivity.isTransientTaskbar();
        boolean isInSetup = !mActivity.isUserSetupComplete() || setupUIVisible;
        boolean isStashedInAppAuto =
                isTransientTaskbar && !mTaskbarSharedState.getTaskbarWasPinned();

        if (ENABLE_TASKBAR_NAVBAR_UNIFICATION) {
            isStashedInAppAuto = isStashedInAppAuto && mTaskbarSharedState.taskbarWasStashedAuto;
        }
        updateStateForFlag(FLAG_STASHED_IN_APP_AUTO, isStashedInAppAuto);
        updateStateForFlag(FLAG_STASHED_IN_APP_SETUP, isInSetup);
        updateStateForFlag(FLAG_IN_SETUP, isInSetup);
        updateStateForFlag(FLAG_STASHED_SMALL_SCREEN, mActivity.isPhoneGestureNavMode());
        // For now, assume we're in an app, since LauncherTaskbarUIController won't be able to tell
        // us that we're paused until a bit later. This avoids flickering upon recreating taskbar.
        updateStateForFlag(FLAG_IN_APP, true);
        updateStateForFlag(FLAG_STASHED_BUBBLE_BAR_ON_PHONE, mActivity.isBubbleBarOnPhone());

        applyState(/* duration = */ 0);

        // Hide the background while stashed so it doesn't show on fast swipes home
        boolean shouldHideTaskbarBackground = mActivity.isPhoneMode() ||
                (enableScalingRevealHomeAnimation() && isTransientTaskbar && isStashed());

        mTaskbarBackgroundAlphaForStash.setValue(shouldHideTaskbarBackground ? 0 : 1);

        if (mTaskbarSharedState.getTaskbarWasPinned()
                || !mTaskbarSharedState.taskbarWasStashedAuto) {
            tryStartTaskbarTimeout();
        }
        notifyStashChange(/* visible */ false, /* stashed */ isStashedInApp());
    }

    /**
     * Returns whether the taskbar can visually stash into a handle based on the current device
     * state.
     */
    public boolean supportsVisualStashing() {
        return !mActivity.isThreeButtonNav() && mControllers.uiController.supportsVisualStashing();
    }

    /**
     * Enables the auto timeout for taskbar stashing. This method should only be used for taskbar
     * testing.
     */
    @VisibleForTesting
    public void enableBlockingTimeoutDuringTests(boolean enableBlockingTimeout) {
        mEnableBlockingTimeoutDuringTests = enableBlockingTimeout;
    }

    /**
     * Sets the flag indicating setup UI is visible
     */
    protected void setSetupUIVisible(boolean isVisible) {
        boolean hideTaskbar = isVisible || !mActivity.isUserSetupComplete();
        updateStateForFlag(FLAG_IN_SETUP, hideTaskbar);
        updateStateForFlag(FLAG_STASHED_IN_APP_SETUP, hideTaskbar);
        applyState(hideTaskbar ? 0 : getStashDuration());
    }

    /**
     * Returns how long the stash/unstash animation should play.
     */
    public long getStashDuration() {
        if (mActivity.isPinnedTaskbar()) {
            return PINNED_TASKBAR_TRANSITION_DURATION;
        }
        return mActivity.isTransientTaskbar() ? TRANSIENT_TASKBAR_STASH_DURATION
                : TASKBAR_STASH_DURATION;
    }

    /**
     * Returns whether the taskbar is currently visually stashed.
     */
    public boolean isStashed() {
        return mIsStashed;
    }

    public boolean isDeviceLocked() {
        return hasAnyFlag(FLAG_STASHED_DEVICE_LOCKED);
    }

    /**
     * Sets the hotseat stashed.
     * b/373429249 - we might change this behavior if we remove the scrim, that's why we're keeping
     * this method
     */
    public void stashHotseat(boolean stash) {
        mControllers.uiController.stashHotseat(stash);
    }

    /**
     * Instantly un-stashes the hotseat.
     * * b/373429249 - we might change this behavior if we remove the scrim, that's why we're
     * keeping this method
     */
    public void unStashHotseatInstantly() {
        mControllers.uiController.unStashHotseatInstantly();
    }

    /**
     * Returns whether the taskbar should be stashed in apps (e.g. user long pressed to stash).
     */
    public boolean isStashedInApp() {
        return hasAnyFlag(FLAGS_STASHED_IN_APP);
    }

    /**
     * Returns whether the taskbar should be stashed in the current LauncherState.
     */
    public boolean isInStashedLauncherState() {
        return (hasAnyFlag(FLAG_IN_STASHED_LAUNCHER_STATE) && supportsVisualStashing());
    }

    private boolean hasAnyFlag(long flagMask) {
        return hasAnyFlag(mState, flagMask);
    }

    private boolean hasAnyFlag(long flags, long flagMask) {
        return (flags & flagMask) != 0;
    }


    /**
     * Returns whether the taskbar is currently visible and not in the process of being stashed.
     */
    public boolean isTaskbarVisibleAndNotStashing() {
        return !mIsStashed && mControllers.taskbarViewController.areIconsVisible();
    }

    public boolean isInApp() {
        return hasAnyFlag(FLAGS_IN_APP);
    }

    /** Returns whether the taskbar is currently in overview screen. */
    public boolean isInOverview() {
        return hasAnyFlag(FLAG_IN_OVERVIEW);
    }

    /** Returns whether the taskbar is currently on launcher home screen. */
    public boolean isOnHome() {
        return !isInOverview() && !isInApp();
    }

    /** Returns whether taskbar is hidden for bubbles. */
    public boolean isHiddenForBubbles() {
        return hasAnyFlag(FLAG_STASHED_FOR_BUBBLES);
    }

    /**
     * Returns the height that taskbar will be touchable.
     */
    public int getTouchableHeight() {
        return mIsStashed
                ? mStashedHeight
                : (mUnstashedHeight + mActivity.getDeviceProfile().taskbarBottomMargin);
    }

    /**
     * Returns the height that taskbar will inset when inside apps.
     *
     * @see android.view.WindowInsets.Type#navigationBars()
     * @see android.view.WindowInsets.Type#systemBars()
     */
    public int getContentHeightToReportToApps() {
        boolean isTransient = mActivity.isTransientTaskbar();
        if (mActivity.isUserSetupComplete() && (mActivity.isPhoneGestureNavMode() || isTransient)) {
            return getStashedHeight();
        }

        if (supportsVisualStashing() && hasAnyFlag(FLAGS_REPORT_STASHED_INSETS_TO_APP)) {
            DeviceProfile dp = mActivity.getDeviceProfile();
            if (hasAnyFlag(FLAG_STASHED_IN_APP_SETUP) && (dp.isTaskbarPresent
                    || mActivity.isPhoneGestureNavMode())) {
                // We always show the back button in SUW but in portrait the SUW layout may not
                // be wide enough to support overlapping the nav bar with its content.
                // We're sending different res values in portrait vs landscape
                return mActivity.getResources().getDimensionPixelSize(R.dimen.taskbar_suw_insets);
            }
            boolean isAnimating = mAnimator != null && mAnimator.isStarted();
            if (!mControllers.stashedHandleViewController.isStashedHandleVisible()
                    && isInApp()
                    && !isAnimating) {
                // We are in a settled state where we're not showing the handle even though taskbar
                // is stashed. This can happen for example when home button is disabled (see
                // StashedHandleViewController#setIsHomeButtonDisabled()).
                return 0;
            }
            return mStashedHeight;
        }

        return mUnstashedHeight;
    }

    /**
     * Returns the height that taskbar will inset when inside apps.
     *
     * @see android.view.WindowInsets.Type#tappableElement()
     */
    public int getTappableHeightToReportToApps() {
        int contentHeight = getContentHeightToReportToApps();
        return contentHeight <= mStashedHeight ? 0 : contentHeight;
    }

    public int getStashedHeight() {
        return mStashedHeight;
    }

    /**
     * Stash or unstashes the transient taskbar, using the default TASKBAR_STASH_DURATION.
     * If bubble bar exists, it will match taskbars stashing behavior.
     * Will not delay taskbar background by default.
     */
    public void updateAndAnimateTransientTaskbar(boolean stash) {
        updateAndAnimateTransientTaskbar(stash, SHOULD_BUBBLES_FOLLOW_DEFAULT_VALUE, false);
    }

    /**
     * Stash or unstashes the transient taskbar, using the default TASKBAR_STASH_DURATION.
     */
    public void updateAndAnimateTransientTaskbar(boolean stash, boolean shouldBubblesFollow) {
        updateAndAnimateTransientTaskbar(stash, shouldBubblesFollow, false);
    }

    /**
     * Stash or unstashes the transient taskbar.
     *
     * @param stash               whether transient taskbar should be stashed.
     * @param shouldBubblesFollow whether bubbles should match taskbars behavior.
     * @param delayTaskbarBackground whether we will delay the taskbar background animation
     */
    public void updateAndAnimateTransientTaskbar(boolean stash, boolean shouldBubblesFollow,
            boolean delayTaskbarBackground) {
        if (!mActivity.isTransientTaskbar() || mActivity.isBubbleBarOnPhone()) {
            return;
        }

        if (stash
                && !mControllers.taskbarAutohideSuspendController
                .isSuspendedForTransientTaskbarInLauncher()
                && mControllers.taskbarAutohideSuspendController
                .isTransientTaskbarStashingSuspended()) {
            // Avoid stashing if autohide is currently suspended.
            return;
        }

        boolean shouldApplyState = false;

        if (delayTaskbarBackground) {
            mControllers.taskbarStashController.updateStateForFlag(FLAG_DELAY_TASKBAR_BG_TAG, true);
            shouldApplyState = true;
        }

        if (hasAnyFlag(FLAG_STASHED_IN_APP_AUTO) != stash) {
            mTaskbarSharedState.taskbarWasStashedAuto = stash;
            updateStateForFlag(FLAG_STASHED_IN_APP_AUTO, stash);
            shouldApplyState = true;
        }

        if (shouldApplyState) {
            applyState();
        }

        // Effectively a no-opp to remove the tag.
        if (delayTaskbarBackground) {
            mControllers.taskbarStashController.updateStateForFlag(FLAG_DELAY_TASKBAR_BG_TAG,
                    false);
            mControllers.taskbarStashController.applyState(0);
        }

        mControllers.bubbleControllers.ifPresent(controllers -> {
            if (shouldBubblesFollow) {
                final boolean willStash = mIsStashedPredicate.test(mState);
                if (willStash != controllers.bubbleStashController.isStashed()) {
                    // Typically bubbles gets stashed / unstashed along with Taskbar, however, if
                    // taskbar is becoming stashed because bubbles is being expanded, we don't want
                    // to stash bubbles.
                    if (willStash) {
                        controllers.bubbleStashController.stashBubbleBar();
                    } else {
                        controllers.bubbleStashController.showBubbleBar(false /* expandBubbles */);
                    }
                }
            }
        });
    }

    /**
     * Stashes transient taskbar after it has timed out.
     */
    private void updateAndAnimateTransientTaskbarForTimeout() {
        // If bubbles are expanded we shouldn't stash them when taskbar is hidden
        // for the timeout.
        boolean bubbleBarExpanded = mControllers.bubbleControllers.isPresent()
                && mControllers.bubbleControllers.get().bubbleBarViewController.isExpanded();
        updateAndAnimateTransientTaskbar(/* stash= */ true,
                /* shouldBubblesFollow= */ !bubbleBarExpanded);
    }

    /** Toggles the Taskbar's stash state. */
    public void toggleTaskbarStash() {
        if (!mActivity.isTransientTaskbar() || !hasAnyFlag(FLAGS_IN_APP)) return;
        updateAndAnimateTransientTaskbar(!hasAnyFlag(FLAG_STASHED_IN_APP_AUTO));
    }

    /**
     * Adds the Taskbar unstash to Hotseat animator to the animator set.
     *
     * This should be used to run a Taskbar unstash to Hotseat animation whose progress matches a
     * swipe progress.
     *
     * @param placeholderDuration a placeholder duration to be used to ensure all full-length
     *                            sub-animations are properly coordinated. This duration should not
     *                            actually be used since this animation tracks a swipe progress.
     */
    protected void addUnstashToHotseatAnimationFromSuw(AnimatorSet animation,
            int placeholderDuration) {
        // Defer any UI updates now to avoid the UI becoming stale when the animation plays.
        mControllers.taskbarViewController.setDeferUpdatesForSUW(true);
        createAnimToIsStashed(
                /* isStashed= */ mActivity.isPhoneMode(),
                placeholderDuration,
                TRANSITION_UNSTASH_SUW_MANUAL,
                /* skipTaskbarBackgroundDelay */ false,
                /* jankTag= */ "SUW_MANUAL");
        animation.addListener(AnimatorListeners.forEndCallback(
                () -> mControllers.taskbarViewController.setDeferUpdatesForSUW(false)));
        animation.play(mAnimator);
    }

    /**
     * Create a stash animation and save to {@link #mAnimator}.
     *
     * @param isStashed             whether it's a stash animation or an unstash animation
     * @param duration              duration of the animation
     * @param animationType         what transition type to play.
     * @param shouldDelayBackground whether we should delay the taskbar bg animation
     * @param jankTag               tag to be used in jank monitor trace.
     */
    private void createAnimToIsStashed(boolean isStashed, long duration,
            @StashAnimation int animationType, boolean shouldDelayBackground, String jankTag) {
        if (animationType == TRANSITION_UNSTASH_SUW_MANUAL && isStashed) {
            // The STASH_ANIMATION_SUW_MANUAL must only be used during an unstash animation.
            Log.e(TAG, "Illegal arguments:Using TRANSITION_UNSTASH_SUW_MANUAL to stash taskbar");
        }

        if (mAnimator != null) {
            mAnimator.cancel();
        }
        mAnimator = new AnimatorSet();
        addJankMonitorListener(
                mAnimator, /* expanding= */ !isStashed, /* tag= */ jankTag);
        final float stashTranslation = mActivity.isPhoneMode() || mActivity.isTransientTaskbar()
                ? 0
                : (mUnstashedHeight - mStashedHeight);

        if (!supportsVisualStashing()) {
            // Just hide/show the icons and background instead of stashing into a handle.
            mAnimator.play(mIconAlphaForStash.animateToValue(isStashed ? 0 : 1)
                    .setDuration(duration));
            mAnimator.playTogether(mTaskbarBackgroundOffset.animateToValue(isStashed ? 1 : 0)
                    .setDuration(duration));
            mAnimator.playTogether(mIconTranslationYForStash.animateToValue(isStashed
                            ? stashTranslation : 0)
                    .setDuration(duration));
            mAnimator.play(mTaskbarImeBgAlpha.animateToValue(
                    (hasAnyFlag(FLAG_STASHED_IME) && isStashed) ? 0 : 1).setDuration(
                    duration));
            mAnimator.addListener(AnimatorListeners.forEndCallback(() -> {
                mAnimator = null;
                mIsStashed = isStashed;
                onIsStashedChanged(mIsStashed);
            }));
            return;
        }

        if (mActivity.isTransientTaskbar()) {
            createTransientAnimToIsStashed(mAnimator, isStashed, duration,
                    shouldDelayBackground, animationType);
        } else {
            createAnimToIsStashed(mAnimator, isStashed, duration, stashTranslation, animationType);
        }

        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mIsStashed = isStashed;
                onIsStashedChanged(mIsStashed);

                cancelTimeoutIfExists();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimator = null;

                if (!mIsStashed) {
                    tryStartTaskbarTimeout();
                }

                // only announce if we are actually animating
                if (duration > 0 && isInApp()) {
                    mControllers.taskbarViewController.announceForAccessibility();
                }
            }
        });
    }

    private void createAnimToIsStashed(AnimatorSet as, boolean isStashed, long duration,
            float stashTranslation, @StashAnimation int animationType) {
        AnimatorSet fullLengthAnimatorSet = new AnimatorSet();
        // Not exactly half and may overlap. See [first|second]HalfDurationScale below.
        AnimatorSet firstHalfAnimatorSet = new AnimatorSet();
        AnimatorSet secondHalfAnimatorSet = new AnimatorSet();

        final float firstHalfDurationScale;
        final float secondHalfDurationScale;

        if (isStashed) {
            firstHalfDurationScale = 0.75f;
            secondHalfDurationScale = 0.5f;

            fullLengthAnimatorSet.play(mIconTranslationYForStash.animateToValue(stashTranslation));
            fullLengthAnimatorSet.play(mTaskbarBackgroundOffset.animateToValue(1));

            firstHalfAnimatorSet.playTogether(
                    mIconAlphaForStash.animateToValue(0),
                    mIconScaleForStash.animateToValue(mActivity.isPhoneMode() ?
                            0 : STASHED_TASKBAR_SCALE)
            );
            secondHalfAnimatorSet.playTogether(
                    mTaskbarStashedHandleAlpha.animateToValue(1)
            );

            if (animationType == TRANSITION_HANDLE_FADE) {
                fullLengthAnimatorSet.setInterpolator(INSTANT);
                firstHalfAnimatorSet.setInterpolator(INSTANT);
            }
        } else {
            firstHalfDurationScale = 0.5f;
            secondHalfDurationScale = 0.75f;

            fullLengthAnimatorSet.playTogether(
                    mIconScaleForStash.animateToValue(1),
                    mIconTranslationYForStash.animateToValue(0));

            final boolean animateBg = animationType != TRANSITION_UNSTASH_SUW_MANUAL;
            if (animateBg) {
                fullLengthAnimatorSet.play(mTaskbarBackgroundOffset.animateToValue(0));
            } else {
                fullLengthAnimatorSet.addListener(AnimatorListeners.forEndCallback(
                        () -> mTaskbarBackgroundOffset.updateValue(0)));
            }

            firstHalfAnimatorSet.playTogether(
                    mTaskbarStashedHandleAlpha.animateToValue(0)
            );
            secondHalfAnimatorSet.playTogether(
                    mIconAlphaForStash.animateToValue(1)
            );

            if (animationType == TRANSITION_HANDLE_FADE) {
                fullLengthAnimatorSet.setInterpolator(FINAL_FRAME);
                secondHalfAnimatorSet.setInterpolator(FINAL_FRAME);
            }
        }

        fullLengthAnimatorSet.play(mControllers.stashedHandleViewController
                .createRevealAnimToIsStashed(isStashed));
        // Return the stashed handle to its default scale in case it was changed as part of the
        // feedforward hint. Note that the reveal animation above also visually scales it.
        fullLengthAnimatorSet.play(mTaskbarStashedHandleHintScale.animateToValue(1f));

        fullLengthAnimatorSet.setDuration(duration);
        firstHalfAnimatorSet.setDuration((long) (duration * firstHalfDurationScale));
        secondHalfAnimatorSet.setDuration((long) (duration * secondHalfDurationScale));
        secondHalfAnimatorSet.setStartDelay((long) (duration * (1 - secondHalfDurationScale)));

        as.playTogether(fullLengthAnimatorSet, firstHalfAnimatorSet,
                secondHalfAnimatorSet);

    }

    private void createTransientAnimToIsStashed(AnimatorSet as, boolean isStashed, long duration,
            boolean shouldDelayBackground, @StashAnimation int animationType) {
        // Target values of the properties this is going to set
        final float backgroundOffsetTarget = isStashed ? 1 : 0;
        final float iconAlphaTarget = isStashed ? 0 : 1;
        final float stashedHandleAlphaTarget = isStashed ? 1 : 0;
        final float backgroundAlphaTarget = isStashed ? 0 : 1;

        // Timing for the alpha values depend on the animation played
        long iconAlphaStartDelay = 0, iconAlphaDuration = 0, backgroundAndHandleAlphaStartDelay = 0,
                backgroundAndHandleAlphaDuration = 0;
        if (duration > 0) {
            if (animationType == TRANSITION_HANDLE_FADE) {
                // When fading, the handle fades in/out at the beginning of the transition with
                // TASKBAR_STASH_ALPHA_DURATION.
                backgroundAndHandleAlphaDuration = TRANSIENT_TASKBAR_STASH_ALPHA_DURATION;
                // The iconAlphaDuration must be set to duration for the skippable interpolators
                // below to work.
                iconAlphaDuration = duration;
            } else {
                iconAlphaStartDelay = TASKBAR_STASH_ALPHA_START_DELAY;
                iconAlphaDuration = TRANSIENT_TASKBAR_STASH_ALPHA_DURATION;
                backgroundAndHandleAlphaDuration = TRANSIENT_TASKBAR_STASH_ALPHA_DURATION;

                if (isStashed) {
                    if (animationType == TRANSITION_HOME_TO_APP) {
                        iconAlphaStartDelay = TASKBAR_STASH_ICON_ALPHA_HOME_TO_APP_START_DELAY;
                    }
                    backgroundAndHandleAlphaStartDelay = iconAlphaStartDelay;
                    backgroundAndHandleAlphaDuration = Math.max(0, duration - iconAlphaStartDelay);
                }

            }
        }

        play(as, mTaskbarStashedHandleAlpha.animateToValue(stashedHandleAlphaTarget),
                backgroundAndHandleAlphaStartDelay,
                backgroundAndHandleAlphaDuration, LINEAR);


        if (enableScalingRevealHomeAnimation()
                && !isStashed
                && shouldDelayBackground) {
            play(as, getTaskbarBackgroundAnimatorWhenNotGoingHome(duration),
                    0, 0, LINEAR);
            as.addListener(AnimatorListeners.forEndCallback(
                    () -> mTaskbarBackgroundAlphaForStash.setValue(backgroundAlphaTarget)));
        } else {
            play(as, mTaskbarBackgroundAlphaForStash.animateToValue(backgroundAlphaTarget),
                    backgroundAndHandleAlphaStartDelay,
                    backgroundAndHandleAlphaDuration, LINEAR);
        }

        // The rest of the animations might be "skipped" in TRANSITION_HANDLE_FADE transitions.
        AnimatorSet skippable = as;
        if (animationType == TRANSITION_HANDLE_FADE) {
            skippable = new AnimatorSet();
            as.play(skippable);
            skippable.setInterpolator(isStashed ? INSTANT : FINAL_FRAME);
        }

        final boolean animateBg = animationType != TRANSITION_UNSTASH_SUW_MANUAL;
        if (animateBg) {
            play(skippable, mTaskbarBackgroundOffset.animateToValue(backgroundOffsetTarget), 0,
                    duration, EMPHASIZED);
        } else {
            skippable.addListener(AnimatorListeners.forEndCallback(
                    () -> mTaskbarBackgroundOffset.updateValue(backgroundOffsetTarget)));
        }

        play(skippable, mIconAlphaForStash.animateToValue(iconAlphaTarget), iconAlphaStartDelay,
                iconAlphaDuration,
                LINEAR);

        if (isStashed) {
            play(skippable, mControllers.taskbarSpringOnStashController.createSpringToStash(),
                    0, duration, LINEAR);
        } else {
            play(skippable, mControllers.taskbarSpringOnStashController.createResetAnimForUnstash(),
                    0, duration, LINEAR);
        }

        mControllers.taskbarViewController.addRevealAnimToIsStashed(skippable, isStashed, duration,
                EMPHASIZED, animationType == TRANSITION_UNSTASH_SUW_MANUAL);

        play(skippable, mControllers.stashedHandleViewController
                .createRevealAnimToIsStashed(isStashed), 0, duration, EMPHASIZED);

        // Return the stashed handle to its default scale in case it was changed as part of the
        // feedforward hint. Note that the reveal animation above also visually scales it.
        skippable.play(mTaskbarStashedHandleHintScale.animateToValue(1f)
                .setDuration(isStashed ? duration / 2 : duration));
    }

    private Animator getTaskbarBackgroundAnimatorWhenNotGoingHome(long duration) {
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(duration);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            // This value is arbitrary.
            private static final float ANIMATED_FRACTION_THRESHOLD = 0.25f;
            private boolean mTaskbarBgAlphaAnimationStarted = false;
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                if (mTaskbarBgAlphaAnimationStarted) {
                    return;
                }

                if (valueAnimator.getAnimatedFraction() >= ANIMATED_FRACTION_THRESHOLD) {
                    if (mUserIsNotGoingHome) {
                        playTaskbarBackgroundAlphaAnimation();
                        mTaskbarBgAlphaAnimationStarted = true;
                    }
                }
            }
        });
        return a;
    }

    /**
     * Sets whether the user is going home based on the current gesture.
     */
    public void setUserIsNotGoingHome(boolean userIsNotGoingHome) {
        mUserIsNotGoingHome = userIsNotGoingHome;
    }

    /**
     * Plays the taskbar background alpha animation if one is not currently playing.
     */
    public void playTaskbarBackgroundAlphaAnimation() {
        if (mTaskbarBackgroundAlphaAnimator != null
                && mTaskbarBackgroundAlphaAnimator.isRunning()) {
            return;
        }
        mTaskbarBackgroundAlphaAnimator = mTaskbarBackgroundAlphaForStash
                .animateToValue(1f)
                .setDuration(mTaskbarBackgroundDuration);
        mTaskbarBackgroundAlphaAnimator.setInterpolator(LINEAR);
        mTaskbarBackgroundAlphaAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mTaskbarBackgroundAlphaAnimator = null;
            }
        });
        mTaskbarBackgroundAlphaAnimator.start();
    }

    private static void play(AnimatorSet as, @Nullable Animator a, long startDelay, long duration,
            Interpolator interpolator) {
        if (a == null) {
            return;
        }
        a.setDuration(duration);
        a.setStartDelay(startDelay);
        a.setInterpolator(interpolator);
        as.play(a);
    }

    private void addJankMonitorListener(
            AnimatorSet animator, boolean expanding, String tag) {
        View v = mControllers.taskbarActivityContext.getDragLayer();
        if (!v.isAttachedToWindow()) {
            // If the task bar drag layer is not attached to window, we don't need to monitor jank
            // (actually we can't pass in an unattached view either).
            return;
        }
        int action = expanding ? InteractionJankMonitor.CUJ_TASKBAR_EXPAND :
                InteractionJankMonitor.CUJ_TASKBAR_COLLAPSE;
        animator.addListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationStart(@NonNull Animator animation) {
                final Configuration.Builder builder =
                        Configuration.Builder.withView(action, v);
                if (tag != null) {
                    builder.setTag(tag);
                }
                InteractionJankMonitor.getInstance().begin(builder);
            }

            @Override
            public void onAnimationSuccess(@NonNull Animator animator) {
                InteractionJankMonitor.getInstance().end(action);
            }

            @Override
            public void onAnimationCancel(@NonNull Animator animation) {
                super.onAnimationCancel(animation);

                InteractionJankMonitor.getInstance().cancel(action);
            }
        });
    }

    /**
     * Creates and starts a partial unstash animation, hinting at the new state that will trigger
     * when long press is detected.
     *
     * @param animateForward Whether we are going towards the new unstashed state or returning to
     *                       the stashed state.
     */
    protected void startUnstashHint(boolean animateForward) {
        if (!isStashed()) {
            // Already unstashed, no need to hint in that direction.
            return;
        }
        mTaskbarStashedHandleHintScale.animateToValue(
                        animateForward ? UNSTASHED_TASKBAR_HANDLE_HINT_SCALE : 1)
                .setDuration(TASKBAR_HINT_STASH_DURATION).start();
    }

    private void onIsStashedChanged(boolean isStashed) {
        mControllers.runAfterInit(() -> {
            mControllers.stashedHandleViewController.onIsStashedChanged(
                    isStashed && supportsVisualStashing());
            mControllers.taskbarInsetsController.onTaskbarOrBubblebarWindowHeightOrInsetsChanged();
        });
    }

    public void applyState() {
        applyState(/* postApplyAction = */ null);
    }

    /** Applies state and performs action after state is applied. */
    public void applyState(@Nullable Runnable postApplyAction) {
        applyState(hasAnyFlag(FLAG_IN_SETUP) ? 0 : TASKBAR_STASH_DURATION, postApplyAction);
    }

    public void applyState(long duration) {
        applyState(duration, /* postApplyAction = */ null);
    }

    private void applyState(long duration, @Nullable Runnable postApplyAction) {
        Animator animator = createApplyStateAnimator(duration);
        if (animator != null) {
            if (postApplyAction != null) {
                // performs action on animation end
                animator.addListener(AnimatorListeners.forEndCallback(postApplyAction));
            }
            animator.start();
        } else if (postApplyAction != null) {
            // animator was not created, just execute the action
            postApplyAction.run();
        }
    }

    public void applyState(long duration, long startDelay) {
        Animator animator = createApplyStateAnimator(duration);
        if (animator != null) {
            animator.setStartDelay(startDelay);
            animator.start();
        }
    }

    /**
     * Returns an animator which applies the latest state if mIsStashed is changed, or {@code null}
     * otherwise.
     */
    @Nullable
    public Animator createApplyStateAnimator(long duration) {
        if (mActivity.isPhoneMode()) {
            return null;
        }
        return mStatePropertyHolder.createSetStateAnimator(mState, duration);
    }

    /**
     * Should be called when a system gesture starts and settles, so we can remove
     * FLAG_STASHED_IN_APP_IME while the gesture is in progress.
     */
    public void setSystemGestureInProgress(boolean inProgress) {
        mIsSystemGestureInProgress = inProgress;
        setStashedImeState();
    }

    private void setStashedImeState() {
        boolean shouldStashForIme = shouldStashForIme();
        if (hasAnyFlag(FLAG_STASHED_IME) != shouldStashForIme) {
            updateStateForFlag(FLAG_STASHED_IME, shouldStashForIme);
            applyState(TASKBAR_STASH_DURATION_FOR_IME, getTaskbarStashStartDelayForIme());
        } else {
            applyState(mControllers.taskbarOverlayController.getCloseDuration());
        }
    }

    /**
     * Should be called when Ime inset is changed to determine if taskbar should be stashed
     */
    public void onImeInsetChanged() {
        setStashedImeState();
    }

    /**
     * When hiding the IME, delay the unstash animation to align with the end of the transition.
     */
    @VisibleForTesting
    long getTaskbarStashStartDelayForIme() {
        if (mIsImeVisible) {
            // Only delay when IME is exiting, not entering.
            return 0;
        }
        // This duration is based on input_method_extract_exit.xml.
        long imeExitDuration = mControllers.taskbarActivityContext.getResources()
                .getInteger(android.R.integer.config_shortAnimTime);
        return imeExitDuration - TASKBAR_STASH_DURATION_FOR_IME;
    }

    /** Called when some system ui state has changed. (See SYSUI_STATE_... in QuickstepContract) */
    public void updateStateForSysuiFlags(long systemUiStateFlags, boolean skipAnim) {
        long animDuration = TASKBAR_STASH_DURATION;
        long startDelay = 0;

        updateStateForFlag(FLAG_STASHED_IN_APP_SYSUI, hasAnyFlag(systemUiStateFlags,
                SYSUI_STATE_DIALOG_SHOWING | (ENABLE_TASKBAR_BEHIND_SHADE.isTrue()
                        ? 0
                        : SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE)
        ));

        boolean stashForBubbles = hasAnyFlag(FLAG_IN_OVERVIEW)
                && hasAnyFlag(systemUiStateFlags, SYSUI_STATE_BUBBLES_EXPANDED)
                && mActivity.isTransientTaskbar();
        updateStateForFlag(FLAG_STASHED_SYSUI,
                hasAnyFlag(systemUiStateFlags, SYSUI_STATE_SCREEN_PINNING) || stashForBubbles);
        updateStateForFlag(FLAG_STASHED_DEVICE_LOCKED,
                SystemUiFlagUtils.isLocked(systemUiStateFlags));

        mIsImeVisible = hasAnyFlag(systemUiStateFlags, SYSUI_STATE_IME_VISIBLE);
        if (updateStateForFlag(FLAG_STASHED_IME, shouldStashForIme())) {
            animDuration = TASKBAR_STASH_DURATION_FOR_IME;
            startDelay = getTaskbarStashStartDelayForIme();
        }

        if (isTaskbarHidden(systemUiStateFlags) && !hasAnyFlag(FLAG_TASKBAR_HIDDEN)) {
            updateStateForFlag(FLAG_TASKBAR_HIDDEN, isTaskbarHidden(systemUiStateFlags));
            applyState(0, SKIP_TOTAL_DREAM_ANIM_DURATION);
        } else {
            updateStateForFlag(FLAG_TASKBAR_HIDDEN, isTaskbarHidden(systemUiStateFlags));
            applyState(skipAnim ? 0 : animDuration, skipAnim ? 0 : startDelay);
        }
    }

    /**
     * We stash when the IME is visible.
     *
     * <p>Do not stash if in small screen, with 3 button nav, and in landscape (or seascape).
     * <p>Do not stash if taskbar is transient.
     * <p>Do not stash if hardware keyboard is attached and taskbar is pinned and IME is docked.
     * <p>Do not stash if a system gesture is started.
     */
    private boolean shouldStashForIme() {
        if (mActivity.isTransientTaskbar()) {
            return false;
        }
        // Do not stash if in small screen, with 3 button nav, and in landscape.
        if (mActivity.isPhoneMode() && mActivity.isThreeButtonNav()
                && mActivity.getDeviceProfile().isLandscape) {
            return false;
        }

        // Do not stash if pinned taskbar, hardware keyboard is attached and no IME is docked
        if (mActivity.isHardwareKeyboard() && mActivity.isPinnedTaskbar()
                && !mActivity.isImeDocked()) {
            return false;
        }

        // Do not stash if hardware keyboard is attached, in 3 button nav and desktop windowing mode
        if (mActivity.isHardwareKeyboard()
                && mActivity.isThreeButtonNav()
                && mControllers.taskbarDesktopModeController
                    .isInDesktopModeAndNotInOverview(mActivity.getDisplayId())) {
            return false;
        }

        // Do not stash if a gesture started.
        if (mIsSystemGestureInProgress) {
            return false;
        }

        return mIsImeVisible;
    }

    /**
     * Updates the proper flag to indicate whether the task bar should be stashed.
     *
     * Note that this only updates the flag. {@link #applyState()} needs to be called separately.
     *
     * @param flag    The flag to update.
     * @param enabled Whether to enable the flag: True will cause the task bar to be stashed /
     *                unstashed.
     * @return Whether the flag state changed.
     */
    public boolean updateStateForFlag(long flag, boolean enabled) {
        long oldState = mState;
        if (enabled) {
            mState |= flag;
        } else {
            mState &= ~flag;
        }
        return mState != oldState;
    }

    /**
     * Called after updateStateForFlag() and applyState() have been called.
     *
     * @param changedFlags The flags that have changed.
     */
    private void onStateChangeApplied(long changedFlags) {
        if (hasAnyFlag(changedFlags, FLAGS_STASHED_IN_APP)) {
            mControllers.uiController.onStashedInAppChanged();
        }
        if (hasAnyFlag(changedFlags, FLAGS_STASHED_IN_APP | FLAGS_IN_APP)) {
            notifyStashChange(/* visible */ hasAnyFlag(FLAGS_IN_APP),
                    /* stashed */ isStashedInApp());
            mControllers.taskbarAutohideSuspendController.updateFlag(
                    TaskbarAutohideSuspendController.FLAG_AUTOHIDE_SUSPEND_IN_LAUNCHER, !isInApp());
        }
        if (hasAnyFlag(changedFlags, FLAG_STASHED_IN_APP_AUTO)) {
            mActivity.getStatsLogManager().logger().log(hasAnyFlag(FLAG_STASHED_IN_APP_AUTO)
                    ? LAUNCHER_TRANSIENT_TASKBAR_HIDE
                    : LAUNCHER_TRANSIENT_TASKBAR_SHOW);
            mControllers.taskbarAutohideSuspendController.updateFlag(
                    TaskbarAutohideSuspendController.FLAG_AUTOHIDE_SUSPEND_TRANSIENT_TASKBAR,
                    !hasAnyFlag(FLAG_STASHED_IN_APP_AUTO));
        }
        if (hasAnyFlag(changedFlags, FLAG_IN_OVERVIEW | FLAG_IN_APP)) {
            mControllers.runAfterInit(() -> mControllers.taskbarInsetsController
                    .onTaskbarOrBubblebarWindowHeightOrInsetsChanged());
            if (mInAppStateAffectsDesktopTasksVisibilityInTaskbar) {
                mControllers.runAfterInit(
                        () -> mControllers.taskbarViewController.commitRunningAppsToUI());
            }
        }
        mActivity.applyForciblyShownFlagWhileTransientTaskbarUnstashed(!isStashedInApp());
    }

    private void notifyStashChange(boolean visible, boolean stashed) {
        mSystemUiProxy.notifyTaskbarStatus(visible, stashed);
        setUpTaskbarSystemAction(visible);
        mControllers.rotationButtonController.onTaskbarStateChange(visible, stashed);
    }

    /**
     * Setup system action for showing Taskbar depending on its visibility.
     */
    public void setUpTaskbarSystemAction(boolean visible) {
        UI_HELPER_EXECUTOR.execute(() -> {
            if (!visible || !mActivity.isTransientTaskbar()
                    || mActivity.isPhoneMode()) {
                mAccessibilityManager.unregisterSystemAction(SYSTEM_ACTION_ID_TASKBAR);
                mIsTaskbarSystemActionRegistered = false;
                return;
            }

            if (!mIsTaskbarSystemActionRegistered) {
                RemoteAction taskbarRemoteAction = new RemoteAction(
                        Icon.createWithResource(mActivity, R.drawable.ic_info_no_shadow),
                        mActivity.getString(R.string.taskbar_a11y_title),
                        mActivity.getString(R.string.taskbar_a11y_title),
                        mTaskbarSharedState.taskbarSystemActionPendingIntent);

                mAccessibilityManager.registerSystemAction(taskbarRemoteAction,
                        SYSTEM_ACTION_ID_TASKBAR);
                mIsTaskbarSystemActionRegistered = true;
            }
        });
    }

    /**
     * Clean up on destroy from TaskbarControllers
     */
    public void onDestroy() {
        // If the controller is destroyed before the animation finishes, we cancel the animation
        // so that we don't finish the CUJ.
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        UI_HELPER_EXECUTOR.execute(
                () -> mAccessibilityManager.unregisterSystemAction(SYSTEM_ACTION_ID_TASKBAR));
    }

    /**
     * Cancels a timeout if any exists.
     */
    public void cancelTimeoutIfExists() {
        if (mTimeoutAlarm.alarmPending()) {
            mTimeoutAlarm.cancelAlarm();
        }
    }

    /**
     * Updates the status of the taskbar timeout.
     *
     * @param isAutohideSuspended If true, cancels any existing timeout
     *                            If false, attempts to re/start the timeout
     */
    public void updateTaskbarTimeout(boolean isAutohideSuspended) {
        if (!mActivity.isTransientTaskbar()) {
            return;
        }
        if (isAutohideSuspended) {
            cancelTimeoutIfExists();
        } else {
            tryStartTaskbarTimeout();
        }
    }

    /**
     * Attempts to start timer to auto hide the taskbar based on time.
     */
    private void tryStartTaskbarTimeout() {
        if (!mActivity.isTransientTaskbar() || mIsStashed || mEnableBlockingTimeoutDuringTests) {
            return;
        }

        cancelTimeoutIfExists();

        mTimeoutAlarm.setOnAlarmListener(this::onTaskbarTimeout);
        mTimeoutAlarm.setAlarm(getTaskbarAutoHideTimeout());
    }

    /**
     * returns appropriate timeout for taskbar to stash depending on accessibility being on/off.
     */
    private long getTaskbarAutoHideTimeout() {
        return mAccessibilityManager.getRecommendedTimeoutMillis((int) NO_TOUCH_TIMEOUT_TO_STASH_MS,
                FLAG_CONTENT_CONTROLS);
    }

    private void onTaskbarTimeout(Alarm alarm) {
        if (mControllers.taskbarAutohideSuspendController.isTransientTaskbarStashingSuspended()) {
            return;
        }
        updateAndAnimateTransientTaskbarForTimeout();
    }

    @VisibleForTesting
    Alarm getTimeoutAlarm() {
        return mTimeoutAlarm;
    }

    @Override
    public void dumpLogs(String prefix, PrintWriter pw) {
        pw.println(prefix + "TaskbarStashController:");

        pw.println(prefix + "\tmStashedHeight=" + mStashedHeight);
        pw.println(prefix + "\tmUnstashedHeight=" + mUnstashedHeight);
        pw.println(prefix + "\tmIsStashed=" + mIsStashed);
        pw.println(prefix + "\tappliedState=" + getStateString(mStatePropertyHolder.mPrevFlags));
        pw.println(prefix + "\tmState=" + getStateString(mState));
        pw.println(prefix + "\tmIsSystemGestureInProgress=" + mIsSystemGestureInProgress);
        pw.println(prefix + "\tmIsImeVisible=" + mIsImeVisible);
    }

    private static String getStateString(long flags) {
        StringJoiner sj = new StringJoiner("|");
        appendFlag(sj, flags, FLAGS_IN_APP, "FLAG_IN_APP");
        appendFlag(sj, flags, FLAG_STASHED_IN_APP_SYSUI, "FLAG_STASHED_IN_APP_SYSUI");
        appendFlag(sj, flags, FLAG_STASHED_IN_APP_SETUP, "FLAG_STASHED_IN_APP_SETUP");
        appendFlag(sj, flags, FLAG_STASHED_IME, "FLAG_STASHED_IN_APP_IME");
        appendFlag(sj, flags, FLAG_IN_STASHED_LAUNCHER_STATE, "FLAG_IN_STASHED_LAUNCHER_STATE");
        appendFlag(sj, flags, FLAG_STASHED_IN_TASKBAR_ALL_APPS, "FLAG_STASHED_IN_TASKBAR_ALL_APPS");
        appendFlag(sj, flags, FLAG_IN_SETUP, "FLAG_IN_SETUP");
        appendFlag(sj, flags, FLAG_STASHED_IN_APP_AUTO, "FLAG_STASHED_IN_APP_AUTO");
        appendFlag(sj, flags, FLAG_STASHED_SYSUI, "FLAG_STASHED_SYSUI");
        appendFlag(sj, flags, FLAG_STASHED_DEVICE_LOCKED, "FLAG_STASHED_DEVICE_LOCKED");
        appendFlag(sj, flags, FLAG_IN_OVERVIEW, "FLAG_IN_OVERVIEW");
        return sj.toString();
    }

    private class StatePropertyHolder {
        private final LongPredicate mStashCondition;

        private boolean mIsStashed;
        private @StashAnimation int mLastStartedTransitionType = TRANSITION_DEFAULT;
        private long mPrevFlags;

        private long mLastUnlockTransitionTimeout = 0;

        StatePropertyHolder(LongPredicate stashCondition) {
            mStashCondition = stashCondition;
        }

        /**
         * Creates an animator (stored in mAnimator) which applies the latest state, potentially
         * creating a new animation (stored in mAnimator).
         *
         * @param flags    The latest flags to apply (see the top of this file).
         * @param duration The length of the animation.
         * @return mAnimator if mIsStashed changed, or {@code null} otherwise.
         */
        @Nullable
        public Animator createSetStateAnimator(long flags, long duration) {
            // We do this when we want to synchronize the app launch and taskbar stash animations.
            if (syncAppLaunchWithTaskbarStash()
                    && hasAnyFlag(FLAG_IGNORE_IN_APP)
                    && hasAnyFlag(flags, FLAG_IN_APP)) {
                flags = flags & ~FLAG_IN_APP;
            }

            boolean isStashed = mStashCondition.test(flags);

            if (DEBUG) {
                String stateString = formatFlagChange(flags, mPrevFlags,
                        TaskbarStashController::getStateString);
                Log.d(TAG, "createSetStateAnimator: flags: " + stateString
                        + ", duration: " + duration
                        + ", isStashed: " + isStashed
                        + ", mIsStashed: " + mIsStashed);
            }

            long changedFlags = mPrevFlags ^ flags;
            if (mPrevFlags != flags) {
                onStateChangeApplied(changedFlags);
                mPrevFlags = flags;
            }

            boolean isUnlockTransition = hasAnyFlag(changedFlags, FLAG_STASHED_DEVICE_LOCKED)
                    && !hasAnyFlag(FLAG_STASHED_DEVICE_LOCKED);
            if (isUnlockTransition) {
                // the launcher might not be resumed at the time the device is considered
                // unlocked (when the keyguard goes away), but possibly shortly afterwards.
                // To play the unlock transition at the time the unstash animation actually happens,
                // this memoizes the state transition for UNLOCK_TRANSITION_MEMOIZATION_MS.
                mLastUnlockTransitionTimeout =
                        SystemClock.elapsedRealtime() + UNLOCK_TRANSITION_MEMOIZATION_MS;
            }

            @StashAnimation int animationType = computeTransitionType(changedFlags);

            // Allow re-starting animation if upgrading from default animation type, otherwise
            // stick with the already started transition.
            boolean transitionTypeChanged = mAnimator != null && mAnimator.isStarted()
                    && mLastStartedTransitionType == TRANSITION_DEFAULT
                    && animationType != TRANSITION_DEFAULT;

            // It is possible for stash=false to be requested by TRANSITION_HOME_TO_APP and
            // TRANSITION_DEFAULT in quick succession. In this case, we should ignore
            // transitionTypeChanged because the animations are exactly the same.
            if (transitionTypeChanged
                    && (!mIsStashed && !isStashed)
                    && animationType == TRANSITION_HOME_TO_APP) {
                transitionTypeChanged = false;
            }

            if (mIsStashed != isStashed || transitionTypeChanged) {
                mIsStashed = isStashed;
                mLastStartedTransitionType = animationType;

                boolean shouldDelayBackground = hasAnyFlag(FLAG_DELAY_TASKBAR_BG_TAG);
                // This sets mAnimator.
                createAnimToIsStashed(mIsStashed, duration, animationType, shouldDelayBackground,
                        computeTaskbarJankMonitorTag(changedFlags));
                return mAnimator;
            }
            return null;
        }

        /** Calculates the tag for CUJ_TASKBAR_EXPAND and CUJ_TASKBAR_COLLAPSE jank traces. */
        private String computeTaskbarJankMonitorTag(long changedFlags) {
            if (hasAnyFlag(changedFlags, FLAG_IN_APP)) {
                // moving in or out of the app
                if (hasAnyFlag(FLAG_IN_APP)) {
                    return "Home to App";
                } else {
                    return "App to Home";
                }
            }
            if (hasAnyFlag(changedFlags, FLAG_STASHED_IN_APP_AUTO)) {
                // stash and unstash with-in the app
                if (hasAnyFlag(FLAG_STASHED_IN_APP_AUTO)) {
                    return "Stashed in app";
                } else {
                    return "Manually unstashed";
                }
            }
            return "";
        }

        private @StashAnimation int computeTransitionType(long changedFlags) {

            boolean hotseatHiddenDuringAppLaunch =
                    !mControllers.uiController.isHotseatIconOnTopWhenAligned()
                            && hasAnyFlag(changedFlags, FLAG_IN_APP);
            if (hotseatHiddenDuringAppLaunch) {
                // When launching an app from the all-apps drawer, the hotseat is hidden behind the
                // drawer. In this case, the navbar must just fade in, without a stash transition,
                // as the taskbar stash animation would otherwise be visible above the all-apps
                // drawer once the hotseat is detached.
                return TRANSITION_HANDLE_FADE;
            }

            boolean isUnlockTransition =
                    SystemClock.elapsedRealtime() < mLastUnlockTransitionTimeout;
            if (isUnlockTransition) {
                // When transitioning to unlocked device, the  hotseat will already be visible on
                // the homescreen, thus do not play an un-stash animation.
                // Keep isUnlockTransition in sync with its counterpart in
                // TaskbarLauncherStateController#onStateChangeApplied.
                return TRANSITION_HANDLE_FADE;
            }

            boolean homeToApp = hasAnyFlag(changedFlags, FLAG_IN_APP) && hasAnyFlag(FLAG_IN_APP);
            if (homeToApp) {
                return TRANSITION_HOME_TO_APP;
            }

            return TRANSITION_DEFAULT;
        }
    }
}
