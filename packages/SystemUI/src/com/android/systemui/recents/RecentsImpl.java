/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.recents;

import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManager.StackId.isHomeOrRecentsStack;
import static android.view.View.MeasureSpec;

import android.app.ActivityManager;
import android.app.ActivityManager.TaskSnapshot;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.MutableBoolean;
import android.view.AppTransitionAnimationSpec;
import android.view.LayoutInflater;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import android.widget.Toast;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.policy.DockedDividerUtils;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DockedTopTaskEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowLastAnimationFrameEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.IterateRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchMostRecentTaskRequestEvent;
import com.android.systemui.recents.events.activity.LaunchNextTaskRequestEvent;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.ActivityPinnedEvent;
import com.android.systemui.recents.events.component.ActivityUnpinnedEvent;
import com.android.systemui.recents.events.component.HidePipMenuEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEndedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEvent;
import com.android.systemui.recents.events.ui.TaskSnapshotChangedEvent;
import com.android.systemui.recents.misc.DozeTrigger;
import com.android.systemui.recents.misc.ForegroundThread;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskGrouping;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm.VisibilityReport;
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.recents.views.TaskStackViewScroller;
import com.android.systemui.recents.views.TaskViewHeader;
import com.android.systemui.recents.views.TaskViewTransform;
import com.android.systemui.recents.views.grid.TaskGridLayoutAlgorithm;
import com.android.systemui.stackdivider.DividerView;
import com.android.systemui.statusbar.phone.NavigationBarGestureHelper;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.ArrayList;

/**
 * An implementation of the Recents component for the current user.  For secondary users, this can
 * be called remotely from the system user.
 */
public class RecentsImpl implements ActivityOptions.OnAnimationFinishedListener {

    private final static String TAG = "RecentsImpl";

    // The minimum amount of time between each recents button press that we will handle
    private final static int MIN_TOGGLE_DELAY_MS = 350;

    // The duration within which the user releasing the alt tab (from when they pressed alt tab)
    // that the fast alt-tab animation will run.  If the user's alt-tab takes longer than this
    // duration, then we will toggle recents after this duration.
    private final static int FAST_ALT_TAB_DELAY_MS = 225;

    public final static String RECENTS_PACKAGE = "com.android.systemui";
    public final static String RECENTS_ACTIVITY = "com.android.systemui.recents.RecentsActivity";

    /**
     * An implementation of TaskStackListener, that allows us to listen for changes to the system
     * task stacks and update recents accordingly.
     */
    class TaskStackListenerImpl extends TaskStackListener {

        @Override
        public void onTaskStackChangedBackground() {
            // Preloads the next task
            RecentsConfiguration config = Recents.getConfiguration();
            if (config.svelteLevel == RecentsConfiguration.SVELTE_NONE) {

                // Load the next task only if we aren't svelte
                SystemServicesProxy ssp = Recents.getSystemServices();
                ActivityManager.RunningTaskInfo runningTaskInfo = ssp.getRunningTask();
                RecentsTaskLoader loader = Recents.getTaskLoader();
                RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
                loader.preloadTasks(plan, -1, false /* includeFrontMostExcludedTask */);

                // This callback is made when a new activity is launched and the old one is paused
                // so ignore the current activity and try and preload the thumbnail for the
                // previous one.
                VisibilityReport visibilityReport;
                synchronized (mDummyStackView) {
                    mDummyStackView.getStack().removeAllTasks(false /* notifyStackChanges */);
                    mDummyStackView.setTasks(plan.getTaskStack(), false /* allowNotify */);
                    updateDummyStackViewLayout(plan.getTaskStack(),
                            getWindowRect(null /* windowRectOverride */));

                    // Launched from app is always the worst case (in terms of how many
                    // thumbnails/tasks visible)
                    RecentsActivityLaunchState launchState = new RecentsActivityLaunchState();
                    launchState.launchedFromApp = true;
                    mDummyStackView.updateLayoutAlgorithm(true /* boundScroll */, launchState);
                    visibilityReport = mDummyStackView.computeStackVisibilityReport();
                }

                RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
                launchOpts.runningTaskId = runningTaskInfo != null ? runningTaskInfo.id : -1;
                launchOpts.numVisibleTasks = visibilityReport.numVisibleTasks;
                launchOpts.numVisibleTaskThumbnails = visibilityReport.numVisibleThumbnails;
                launchOpts.onlyLoadForCache = true;
                launchOpts.onlyLoadPausedActivities = true;
                launchOpts.loadThumbnails = true;
                loader.loadTasks(mContext, plan, launchOpts);
            }
        }

        @Override
        public void onActivityPinned(String packageName, int taskId) {
            // This time needs to be fetched the same way the last active time is fetched in
            // {@link TaskRecord#touchActiveTime}
            Recents.getConfiguration().getLaunchState().launchedFromPipApp = true;
            Recents.getConfiguration().getLaunchState().launchedWithNextPipApp = false;
            EventBus.getDefault().send(new ActivityPinnedEvent(taskId));
            consumeInstanceLoadPlan();
            sLastPipTime = System.currentTimeMillis();
        }

        @Override
        public void onActivityUnpinned() {
            EventBus.getDefault().send(new ActivityUnpinnedEvent());
            sLastPipTime = -1;
        }

        @Override
        public void onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot) {
            EventBus.getDefault().send(new TaskSnapshotChangedEvent(taskId, snapshot));
        }
    }

    protected static RecentsTaskLoadPlan sInstanceLoadPlan;
    // Stores the last pinned task time
    protected static long sLastPipTime = -1;

    protected Context mContext;
    protected Handler mHandler;
    TaskStackListenerImpl mTaskStackListener;
    boolean mDraggingInRecents;
    boolean mLaunchedWhileDocking;

    // Task launching
    Rect mTaskStackBounds = new Rect();
    TaskViewTransform mTmpTransform = new TaskViewTransform();
    int mStatusBarHeight;
    int mNavBarHeight;
    int mNavBarWidth;
    int mTaskBarHeight;

    // Header (for transition)
    TaskViewHeader mHeaderBar;
    final Object mHeaderBarLock = new Object();
    protected TaskStackView mDummyStackView;

    // Variables to keep track of if we need to start recents after binding
    protected boolean mTriggeredFromAltTab;
    protected long mLastToggleTime;
    DozeTrigger mFastAltTabTrigger = new DozeTrigger(FAST_ALT_TAB_DELAY_MS, new Runnable() {
        @Override
        public void run() {
            // When this fires, then the user has not released alt-tab for at least
            // FAST_ALT_TAB_DELAY_MS milliseconds
            showRecents(mTriggeredFromAltTab, false /* draggingInRecents */, true /* animate */,
                    false /* reloadTasks */, false /* fromHome */,
                    DividerView.INVALID_RECENTS_GROW_TARGET);
        }
    });

    protected Bitmap mThumbTransitionBitmapCache;

    public RecentsImpl(Context context) {
        mContext = context;
        mHandler = new Handler();

        // Initialize the static foreground thread
        ForegroundThread.get();

        // Register the task stack listener
        mTaskStackListener = new TaskStackListenerImpl();
        SystemServicesProxy ssp = Recents.getSystemServices();
        ssp.registerTaskStackListener(mTaskStackListener);

        // Initialize the static configuration resources
        mDummyStackView = new TaskStackView(mContext);
        reloadResources();
    }

    public void onBootCompleted() {
        // When we start, preload the data associated with the previous recent tasks.
        // We can use a new plan since the caches will be the same.
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, -1, false /* includeFrontMostExcludedTask */);
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.numVisibleTasks = loader.getIconCacheSize();
        launchOpts.numVisibleTaskThumbnails = loader.getThumbnailCacheSize();
        launchOpts.onlyLoadForCache = true;
        loader.loadTasks(mContext, plan, launchOpts);
    }

    public void onConfigurationChanged() {
        reloadResources();
        synchronized (mDummyStackView) {
            mDummyStackView.reloadOnConfigurationChange();
        }
    }

    /**
     * This is only called from the system user's Recents.  Secondary users will instead proxy their
     * visibility change events through to the system user via
     * {@link Recents#onBusEvent(RecentsVisibilityChangedEvent)}.
     */
    public void onVisibilityChanged(Context context, boolean visible) {
        Recents.getSystemServices().setRecentsVisibility(visible);
    }

    /**
     * This is only called from the system user's Recents.  Secondary users will instead proxy their
     * visibility change events through to the system user via
     * {@link Recents#onBusEvent(ScreenPinningRequestEvent)}.
     */
    public void onStartScreenPinning(Context context, int taskId) {
        SystemUIApplication app = (SystemUIApplication) context;
        StatusBar statusBar = app.getComponent(StatusBar.class);
        if (statusBar != null) {
            statusBar.showScreenPinningRequest(taskId, false);
        }
    }

    public void showRecents(boolean triggeredFromAltTab, boolean draggingInRecents,
            boolean animate, boolean launchedWhileDockingTask, boolean fromHome,
            int growTarget) {
        mTriggeredFromAltTab = triggeredFromAltTab;
        mDraggingInRecents = draggingInRecents;
        mLaunchedWhileDocking = launchedWhileDockingTask;
        if (mFastAltTabTrigger.isAsleep()) {
            // Fast alt-tab duration has elapsed, fall through to showing Recents and reset
            mFastAltTabTrigger.stopDozing();
        } else if (mFastAltTabTrigger.isDozing()) {
            // Fast alt-tab duration has not elapsed.  If this is triggered by a different
            // showRecents() call, then ignore that call for now.
            // TODO: We can not handle quick tabs that happen between the initial showRecents() call
            //       that started the activity and the activity starting up.  The severity of this
            //       is inversely proportional to the FAST_ALT_TAB_DELAY_MS duration though.
            if (!triggeredFromAltTab) {
                return;
            }
            mFastAltTabTrigger.stopDozing();
        } else if (triggeredFromAltTab) {
            // The fast alt-tab detector is not yet running, so start the trigger and wait for the
            // hideRecents() call, or for the fast alt-tab duration to elapse
            mFastAltTabTrigger.startDozing();
            return;
        }

        try {
            // Check if the top task is in the home stack, and start the recents activity
            SystemServicesProxy ssp = Recents.getSystemServices();
            boolean forceVisible = launchedWhileDockingTask || draggingInRecents;
            MutableBoolean isHomeStackVisible = new MutableBoolean(forceVisible);
            if (forceVisible || !ssp.isRecentsActivityVisible(isHomeStackVisible)) {
                ActivityManager.RunningTaskInfo runningTask = ssp.getRunningTask();
                startRecentsActivity(runningTask, isHomeStackVisible.value || fromHome, animate,
                        growTarget);
            }
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Failed to launch RecentsActivity", e);
        }
    }

    public void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (triggeredFromAltTab && mFastAltTabTrigger.isDozing()) {
            // The user has released alt-tab before the trigger has run, so just show the next
            // task immediately
            showNextTask();

            // Cancel the fast alt-tab trigger
            mFastAltTabTrigger.stopDozing();
            return;
        }

        // Defer to the activity to handle hiding recents, if it handles it, then it must still
        // be visible
        EventBus.getDefault().post(new HideRecentsEvent(triggeredFromAltTab,
                triggeredFromHomeKey));
    }

    public void toggleRecents(int growTarget) {
        // Skip this toggle if we are already waiting to trigger recents via alt-tab
        if (mFastAltTabTrigger.isDozing()) {
            return;
        }

        mDraggingInRecents = false;
        mLaunchedWhileDocking = false;
        mTriggeredFromAltTab = false;

        try {
            SystemServicesProxy ssp = Recents.getSystemServices();
            MutableBoolean isHomeStackVisible = new MutableBoolean(true);
            long elapsedTime = SystemClock.elapsedRealtime() - mLastToggleTime;

            if (ssp.isRecentsActivityVisible(isHomeStackVisible)) {
                RecentsDebugFlags debugFlags = Recents.getDebugFlags();
                RecentsConfiguration config = Recents.getConfiguration();
                RecentsActivityLaunchState launchState = config.getLaunchState();
                if (!launchState.launchedWithAltTab) {
                    // Has the user tapped quickly?
                    boolean isQuickTap = elapsedTime < ViewConfiguration.getDoubleTapTimeout();
                    if (Recents.getConfiguration().isGridEnabled) {
                        if (isQuickTap) {
                            EventBus.getDefault().post(new LaunchNextTaskRequestEvent());
                        } else {
                            EventBus.getDefault().post(new LaunchMostRecentTaskRequestEvent());
                        }
                    } else {
                        if (!debugFlags.isPagingEnabled() || isQuickTap) {
                            // Launch the next focused task
                            EventBus.getDefault().post(new LaunchNextTaskRequestEvent());
                        } else {
                            // Notify recents to move onto the next task
                            EventBus.getDefault().post(new IterateRecentsEvent());
                        }
                    }
                } else {
                    // If the user has toggled it too quickly, then just eat up the event here (it's
                    // better than showing a janky screenshot).
                    // NOTE: Ideally, the screenshot mechanism would take the window transform into
                    // account
                    if (elapsedTime < MIN_TOGGLE_DELAY_MS) {
                        return;
                    }

                    EventBus.getDefault().post(new ToggleRecentsEvent());
                    mLastToggleTime = SystemClock.elapsedRealtime();
                }
                return;
            } else {
                // If the user has toggled it too quickly, then just eat up the event here (it's
                // better than showing a janky screenshot).
                // NOTE: Ideally, the screenshot mechanism would take the window transform into
                // account
                if (elapsedTime < MIN_TOGGLE_DELAY_MS) {
                    return;
                }

                // Otherwise, start the recents activity
                ActivityManager.RunningTaskInfo runningTask = ssp.getRunningTask();
                startRecentsActivity(runningTask, isHomeStackVisible.value, true /* animate */,
                        growTarget);

                // Only close the other system windows if we are actually showing recents
                ssp.sendCloseSystemWindows(StatusBar.SYSTEM_DIALOG_REASON_RECENT_APPS);
                mLastToggleTime = SystemClock.elapsedRealtime();
            }
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Failed to launch RecentsActivity", e);
        }
    }

    public void preloadRecents() {
        // Preload only the raw task list into a new load plan (which will be consumed by the
        // RecentsActivity) only if there is a task to animate to.
        SystemServicesProxy ssp = Recents.getSystemServices();
        MutableBoolean isHomeStackVisible = new MutableBoolean(true);
        if (!ssp.isRecentsActivityVisible(isHomeStackVisible)) {
            ActivityManager.RunningTaskInfo runningTask = ssp.getRunningTask();
            if (runningTask == null) {
                return;
            }

            RecentsTaskLoader loader = Recents.getTaskLoader();
            sInstanceLoadPlan = loader.createLoadPlan(mContext);
            loader.preloadTasks(sInstanceLoadPlan, runningTask.id, !isHomeStackVisible.value);
            TaskStack stack = sInstanceLoadPlan.getTaskStack();
            if (stack.getTaskCount() > 0) {
                // Only preload the icon (but not the thumbnail since it may not have been taken for
                // the pausing activity)
                preloadIcon(runningTask.id);

                // At this point, we don't know anything about the stack state.  So only calculate
                // the dimensions of the thumbnail that we need for the transition into Recents, but
                // do not draw it until we construct the activity options when we start Recents
                updateHeaderBarLayout(stack, null /* window rect override*/);
            }
        }
    }

    public void cancelPreloadingRecents() {
        // Do nothing
    }

    public void onDraggingInRecents(float distanceFromTop) {
        EventBus.getDefault().sendOntoMainThread(new DraggingInRecentsEvent(distanceFromTop));
    }

    public void onDraggingInRecentsEnded(float velocity) {
        EventBus.getDefault().sendOntoMainThread(new DraggingInRecentsEndedEvent(velocity));
    }

    public void onShowCurrentUserToast(int msgResId, int msgLength) {
        Toast.makeText(mContext, msgResId, msgLength).show();
    }

    /**
     * Transitions to the next recent task in the stack.
     */
    public void showNextTask() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, -1, false /* includeFrontMostExcludedTask */);
        TaskStack focusedStack = plan.getTaskStack();

        // Return early if there are no tasks in the focused stack
        if (focusedStack == null || focusedStack.getTaskCount() == 0) return;

        // Return early if there is no running task
        ActivityManager.RunningTaskInfo runningTask = ssp.getRunningTask();
        if (runningTask == null) return;

        // Find the task in the recents list
        boolean isRunningTaskInHomeStack = SystemServicesProxy.isHomeStack(runningTask.stackId);
        ArrayList<Task> tasks = focusedStack.getStackTasks();
        Task toTask = null;
        ActivityOptions launchOpts = null;
        int taskCount = tasks.size();
        for (int i = taskCount - 1; i >= 1; i--) {
            Task task = tasks.get(i);
            if (isRunningTaskInHomeStack) {
                toTask = tasks.get(i - 1);
                launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                        R.anim.recents_launch_next_affiliated_task_target,
                        R.anim.recents_fast_toggle_app_home_exit);
                break;
            } else if (task.key.id == runningTask.id) {
                toTask = tasks.get(i - 1);
                launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                        R.anim.recents_launch_prev_affiliated_task_target,
                        R.anim.recents_launch_prev_affiliated_task_source);
                break;
            }
        }

        // Return early if there is no next task
        if (toTask == null) {
            ssp.startInPlaceAnimationOnFrontMostApplication(
                    ActivityOptions.makeCustomInPlaceAnimation(mContext,
                            R.anim.recents_launch_prev_affiliated_task_bounce));
            return;
        }

        // Launch the task
        ssp.startActivityFromRecents(
                mContext, toTask.key, toTask.title, launchOpts, INVALID_STACK_ID,
                null /* resultListener */);
    }

    /**
     * Transitions to the next affiliated task.
     */
    public void showRelativeAffiliatedTask(boolean showNextTask) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, -1, false /* includeFrontMostExcludedTask */);
        TaskStack focusedStack = plan.getTaskStack();

        // Return early if there are no tasks in the focused stack
        if (focusedStack == null || focusedStack.getTaskCount() == 0) return;

        // Return early if there is no running task (can't determine affiliated tasks in this case)
        ActivityManager.RunningTaskInfo runningTask = ssp.getRunningTask();
        if (runningTask == null) return;
        // Return early if the running task is in the home/recents stack (optimization)
        if (isHomeOrRecentsStack(runningTask.stackId)) return;

        // Find the task in the recents list
        ArrayList<Task> tasks = focusedStack.getStackTasks();
        Task toTask = null;
        ActivityOptions launchOpts = null;
        int taskCount = tasks.size();
        int numAffiliatedTasks = 0;
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks.get(i);
            if (task.key.id == runningTask.id) {
                TaskGrouping group = task.group;
                Task.TaskKey toTaskKey;
                if (showNextTask) {
                    toTaskKey = group.getNextTaskInGroup(task);
                    launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                            R.anim.recents_launch_next_affiliated_task_target,
                            R.anim.recents_launch_next_affiliated_task_source);
                } else {
                    toTaskKey = group.getPrevTaskInGroup(task);
                    launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                            R.anim.recents_launch_prev_affiliated_task_target,
                            R.anim.recents_launch_prev_affiliated_task_source);
                }
                if (toTaskKey != null) {
                    toTask = focusedStack.findTaskWithId(toTaskKey.id);
                }
                numAffiliatedTasks = group.getTaskCount();
                break;
            }
        }

        // Return early if there is no next task
        if (toTask == null) {
            if (numAffiliatedTasks > 1) {
                if (showNextTask) {
                    ssp.startInPlaceAnimationOnFrontMostApplication(
                            ActivityOptions.makeCustomInPlaceAnimation(mContext,
                                    R.anim.recents_launch_next_affiliated_task_bounce));
                } else {
                    ssp.startInPlaceAnimationOnFrontMostApplication(
                            ActivityOptions.makeCustomInPlaceAnimation(mContext,
                                    R.anim.recents_launch_prev_affiliated_task_bounce));
                }
            }
            return;
        }

        // Keep track of actually launched affiliated tasks
        MetricsLogger.count(mContext, "overview_affiliated_task_launch", 1);

        // Launch the task
        ssp.startActivityFromRecents(
                mContext, toTask.key, toTask.title, launchOpts, INVALID_STACK_ID,
                null /* resultListener */);
    }

    public void showNextAffiliatedTask() {
        // Keep track of when the affiliated task is triggered
        MetricsLogger.count(mContext, "overview_affiliated_task_next", 1);
        showRelativeAffiliatedTask(true);
    }

    public void showPrevAffiliatedTask() {
        // Keep track of when the affiliated task is triggered
        MetricsLogger.count(mContext, "overview_affiliated_task_prev", 1);
        showRelativeAffiliatedTask(false);
    }

    public void dockTopTask(int topTaskId, int dragMode,
            int stackCreateMode, Rect initialBounds) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Make sure we inform DividerView before we actually start the activity so we can change
        // the resize mode already.
        if (ssp.moveTaskToDockedStack(topTaskId, stackCreateMode, initialBounds)) {
            EventBus.getDefault().send(new DockedTopTaskEvent(dragMode, initialBounds));
            showRecents(
                    false /* triggeredFromAltTab */,
                    dragMode == NavigationBarGestureHelper.DRAG_MODE_RECENTS,
                    false /* animate */,
                    true /* launchedWhileDockingTask*/,
                    false /* fromHome */,
                    DividerView.INVALID_RECENTS_GROW_TARGET);
        }
    }

    /**
     * Returns the preloaded load plan and invalidates it.
     */
    public static RecentsTaskLoadPlan consumeInstanceLoadPlan() {
        RecentsTaskLoadPlan plan = sInstanceLoadPlan;
        sInstanceLoadPlan = null;
        return plan;
    }

    /**
     * @return the time at which a task last entered picture-in-picture.
     */
    public static long getLastPipTime() {
        return sLastPipTime;
    }

    /**
     * Clears the time at which a task last entered picture-in-picture.
     */
    public static void clearLastPipTime() {
        sLastPipTime = -1;
    }

    /**
     * Reloads all the resources for the current configuration.
     */
    private void reloadResources() {
        Resources res = mContext.getResources();

        mStatusBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mNavBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        mNavBarWidth = res.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_width);
        mTaskBarHeight = TaskStackLayoutAlgorithm.getDimensionForDevice(mContext,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height_tablet_land,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height_tablet_land,
                R.dimen.recents_grid_task_view_header_height);

        LayoutInflater inflater = LayoutInflater.from(mContext);
        mHeaderBar = (TaskViewHeader) inflater.inflate(R.layout.recents_task_view_header,
                null, false);
        mHeaderBar.setLayoutDirection(res.getConfiguration().getLayoutDirection());
    }

    private void updateDummyStackViewLayout(TaskStack stack, Rect windowRect) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        Rect displayRect = ssp.getDisplayRect();
        Rect systemInsets = new Rect();
        ssp.getStableInsets(systemInsets);

        // When docked, the nav bar insets are consumed and the activity is measured without insets.
        // However, the window bounds include the insets, so we need to subtract them here to make
        // them identical.
        if (ssp.hasDockedTask()) {
            windowRect.bottom -= systemInsets.bottom;
            systemInsets.bottom = 0;
        }
        calculateWindowStableInsets(systemInsets, windowRect);
        windowRect.offsetTo(0, 0);

        synchronized (mDummyStackView) {
            TaskStackLayoutAlgorithm stackLayout = mDummyStackView.getStackAlgorithm();

            // Rebind the header bar and draw it for the transition
            stackLayout.setSystemInsets(systemInsets);
            if (stack != null) {
                stackLayout.getTaskStackBounds(displayRect, windowRect, systemInsets.top,
                        systemInsets.left, systemInsets.right, mTaskStackBounds);
                stackLayout.reset();
                stackLayout.initialize(displayRect, windowRect, mTaskStackBounds,
                        TaskStackLayoutAlgorithm.StackState.getStackStateForStack(stack));
            }
        }
    }

    private Rect getWindowRect(Rect windowRectOverride) {
       return windowRectOverride != null
                ? new Rect(windowRectOverride)
                : Recents.getSystemServices().getWindowRect();
    }

    /**
     * Prepares the header bar layout for the next transition, if the task view bounds has changed
     * since the last call, it will attempt to re-measure and layout the header bar to the new size.
     *
     * @param stack the stack to initialize the stack layout with
     * @param windowRectOverride the rectangle to use when calculating the stack state which can
     *                           be different from the current window rect if recents is resizing
     *                           while being launched
     */
    private void updateHeaderBarLayout(TaskStack stack, Rect windowRectOverride) {
        Rect windowRect = getWindowRect(windowRectOverride);
        int taskViewWidth = 0;
        boolean useGridLayout = false;
        synchronized (mDummyStackView) {
            useGridLayout = mDummyStackView.useGridLayout();
            updateDummyStackViewLayout(stack, windowRect);
            if (stack != null) {
                TaskStackLayoutAlgorithm stackLayout = mDummyStackView.getStackAlgorithm();
                mDummyStackView.getStack().removeAllTasks(false /* notifyStackChanges */);
                mDummyStackView.setTasks(stack, false /* allowNotifyStackChanges */);
                // Get the width of a task view so that we know how wide to draw the header bar.
                if (useGridLayout) {
                    TaskGridLayoutAlgorithm gridLayout = mDummyStackView.getGridAlgorithm();
                    gridLayout.initialize(windowRect);
                    taskViewWidth = (int) gridLayout.getTransform(0 /* taskIndex */,
                            stack.getTaskCount(), new TaskViewTransform(),
                            stackLayout).rect.width();
                } else {
                    Rect taskViewBounds = stackLayout.getUntransformedTaskViewBounds();
                    if (!taskViewBounds.isEmpty()) {
                        taskViewWidth = taskViewBounds.width();
                    }
                }
            }
        }

        if (stack != null && taskViewWidth > 0) {
            synchronized (mHeaderBarLock) {
                if (mHeaderBar.getMeasuredWidth() != taskViewWidth ||
                        mHeaderBar.getMeasuredHeight() != mTaskBarHeight) {
                    if (useGridLayout) {
                        mHeaderBar.setShouldDarkenBackgroundColor(true);
                        mHeaderBar.setNoUserInteractionState();
                    }
                    mHeaderBar.forceLayout();
                    mHeaderBar.measure(
                            MeasureSpec.makeMeasureSpec(taskViewWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(mTaskBarHeight, MeasureSpec.EXACTLY));
                }
                mHeaderBar.layout(0, 0, taskViewWidth, mTaskBarHeight);
            }

            // Update the transition bitmap to match the new header bar height
            if (mThumbTransitionBitmapCache == null ||
                    (mThumbTransitionBitmapCache.getWidth() != taskViewWidth) ||
                    (mThumbTransitionBitmapCache.getHeight() != mTaskBarHeight)) {
                mThumbTransitionBitmapCache = Bitmap.createBitmap(taskViewWidth,
                        mTaskBarHeight, Bitmap.Config.ARGB_8888);
            }
        }
    }

    /**
     * Given the stable insets and the rect for our window, calculates the insets that affect our
     * window.
     */
    private void calculateWindowStableInsets(Rect inOutInsets, Rect windowRect) {
        Rect displayRect = Recents.getSystemServices().getDisplayRect();

        // Display rect without insets - available app space
        Rect appRect = new Rect(displayRect);
        appRect.inset(inOutInsets);

        // Our window intersected with available app space
        Rect windowRectWithInsets = new Rect(windowRect);
        windowRectWithInsets.intersect(appRect);
        inOutInsets.left = windowRectWithInsets.left - windowRect.left;
        inOutInsets.top = windowRectWithInsets.top - windowRect.top;
        inOutInsets.right = windowRect.right - windowRectWithInsets.right;
        inOutInsets.bottom = windowRect.bottom - windowRectWithInsets.bottom;
    }

    /**
     * Preloads the icon of a task.
     */
    private void preloadIcon(int runningTaskId) {
        // Ensure that we load the running task's icon
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.runningTaskId = runningTaskId;
        launchOpts.loadThumbnails = false;
        launchOpts.onlyLoadForCache = true;
        Recents.getTaskLoader().loadTasks(mContext, sInstanceLoadPlan, launchOpts);
    }

    /**
     * Creates the activity options for a unknown state->recents transition.
     */
    protected ActivityOptions getUnknownTransitionActivityOptions() {
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_unknown_enter,
                R.anim.recents_from_unknown_exit,
                mHandler, null);
    }

    /**
     * Creates the activity options for a home->recents transition.
     */
    protected ActivityOptions getHomeTransitionActivityOptions() {
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_launcher_enter,
                R.anim.recents_from_launcher_exit,
                mHandler, null);
    }

    /**
     * Creates the activity options for an app->recents transition.
     */
    private ActivityOptions getThumbnailTransitionActivityOptions(
            ActivityManager.RunningTaskInfo runningTask, Rect windowOverrideRect) {
        if (runningTask != null && runningTask.stackId == FREEFORM_WORKSPACE_STACK_ID) {
            ArrayList<AppTransitionAnimationSpec> specs = new ArrayList<>();
            ArrayList<Task> tasks;
            TaskStackLayoutAlgorithm stackLayout;
            TaskStackViewScroller stackScroller;

            synchronized (mDummyStackView) {
                tasks = mDummyStackView.getStack().getStackTasks();
                stackLayout = mDummyStackView.getStackAlgorithm();
                stackScroller = mDummyStackView.getScroller();

                mDummyStackView.updateLayoutAlgorithm(true /* boundScroll */);
                mDummyStackView.updateToInitialState();
            }

            for (int i = tasks.size() - 1; i >= 0; i--) {
                Task task = tasks.get(i);
                if (task.isFreeformTask()) {
                    mTmpTransform = stackLayout.getStackTransformScreenCoordinates(task,
                            stackScroller.getStackScroll(), mTmpTransform, null,
                            windowOverrideRect);
                    Bitmap thumbnail = drawThumbnailTransitionBitmap(task, mTmpTransform,
                            mThumbTransitionBitmapCache);
                    Rect toTaskRect = new Rect();
                    mTmpTransform.rect.round(toTaskRect);
                    specs.add(new AppTransitionAnimationSpec(task.key.id, thumbnail, toTaskRect));
                }
            }
            AppTransitionAnimationSpec[] specsArray = new AppTransitionAnimationSpec[specs.size()];
            specs.toArray(specsArray);
            return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mDummyStackView,
                    specsArray, mHandler, null, this);
        } else {
            // Update the destination rect
            Task toTask = new Task();
            TaskViewTransform toTransform = getThumbnailTransitionTransform(mDummyStackView, toTask,
                    windowOverrideRect);
            Bitmap thumbnail = drawThumbnailTransitionBitmap(toTask, toTransform,
                            mThumbTransitionBitmapCache);
            if (thumbnail != null) {
                RectF toTaskRect = toTransform.rect;
                return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mDummyStackView,
                        thumbnail, (int) toTaskRect.left, (int) toTaskRect.top,
                        (int) toTaskRect.width(), (int) toTaskRect.height(), mHandler, null);
            }
            // If both the screenshot and thumbnail fails, then just fall back to the default transition
            return getUnknownTransitionActivityOptions();
        }
    }

    /**
     * Returns the transition rect for the given task id.
     */
    private TaskViewTransform getThumbnailTransitionTransform(TaskStackView stackView,
            Task runningTaskOut, Rect windowOverrideRect) {
        // Find the running task in the TaskStack
        TaskStack stack = stackView.getStack();
        Task launchTask = stack.getLaunchTarget();
        if (launchTask != null) {
            runningTaskOut.copyFrom(launchTask);
        } else {
            // If no task is specified or we can not find the task just use the front most one
            launchTask = stack.getStackFrontMostTask(true /* includeFreeform */);
            runningTaskOut.copyFrom(launchTask);
        }

        // Get the transform for the running task
        stackView.updateLayoutAlgorithm(true /* boundScroll */);
        stackView.updateToInitialState();
        stackView.getStackAlgorithm().getStackTransformScreenCoordinates(launchTask,
                stackView.getScroller().getStackScroll(), mTmpTransform, null, windowOverrideRect);
        return mTmpTransform;
    }

    /**
     * Draws the header of a task used for the window animation into a bitmap.
     */
    private Bitmap drawThumbnailTransitionBitmap(Task toTask, TaskViewTransform toTransform,
            Bitmap thumbnail) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (toTransform != null && toTask.key != null) {
            synchronized (mHeaderBarLock) {
                boolean disabledInSafeMode = !toTask.isSystemApp && ssp.isInSafeMode();
                mHeaderBar.onTaskViewSizeChanged((int) toTransform.rect.width(),
                        (int) toTransform.rect.height());
                if (RecentsDebugFlags.Static.EnableTransitionThumbnailDebugMode) {
                    thumbnail.eraseColor(0xFFff0000);
                } else {
                    thumbnail.eraseColor(0);
                    Canvas c = new Canvas(thumbnail);
                    // Workaround for b/27815919, reset the callback so that we do not trigger an
                    // invalidate on the header bar as a result of updating the icon
                    Drawable icon = mHeaderBar.getIconView().getDrawable();
                    if (icon != null) {
                        icon.setCallback(null);
                    }
                    mHeaderBar.bindToTask(toTask, false /* touchExplorationEnabled */,
                            disabledInSafeMode);
                    mHeaderBar.onTaskDataLoaded();
                    mHeaderBar.setDimAlpha(toTransform.dimAlpha);
                    mHeaderBar.draw(c);
                    c.setBitmap(null);
                }
            }
            return thumbnail.createAshmemBitmap();
        }
        return null;
    }

    /**
     * Shows the recents activity
     */
    protected void startRecentsActivity(ActivityManager.RunningTaskInfo runningTask,
            boolean isHomeStackVisible, boolean animate, int growTarget) {
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        SystemServicesProxy ssp = Recents.getSystemServices();
        boolean isBlacklisted = (runningTask != null)
                ? ssp.isBlackListedActivity(runningTask.baseActivity.getClassName())
                : false;

        int runningTaskId = !mLaunchedWhileDocking && !isBlacklisted && (runningTask != null)
                ? runningTask.id
                : -1;

        // In the case where alt-tab is triggered, we never get a preloadRecents() call, so we
        // should always preload the tasks now. If we are dragging in recents, reload them as
        // the stacks might have changed.
        if (mLaunchedWhileDocking || mTriggeredFromAltTab || sInstanceLoadPlan == null) {
            // Create a new load plan if preloadRecents() was never triggered
            sInstanceLoadPlan = loader.createLoadPlan(mContext);
        }
        if (mLaunchedWhileDocking || mTriggeredFromAltTab || !sInstanceLoadPlan.hasTasks()) {
            loader.preloadTasks(sInstanceLoadPlan, runningTaskId, !isHomeStackVisible);
        }

        TaskStack stack = sInstanceLoadPlan.getTaskStack();
        boolean hasRecentTasks = stack.getTaskCount() > 0;
        boolean useThumbnailTransition = (runningTask != null) && !isHomeStackVisible &&
                hasRecentTasks;

        // Update the launch state that we need in updateHeaderBarLayout()
        launchState.launchedFromHome = !useThumbnailTransition && !mLaunchedWhileDocking;
        launchState.launchedFromApp = useThumbnailTransition || mLaunchedWhileDocking;
        launchState.launchedFromBlacklistedApp = launchState.launchedFromApp && isBlacklisted;
        launchState.launchedFromPipApp = false;
        launchState.launchedWithNextPipApp =
                stack.isNextLaunchTargetPip(RecentsImpl.getLastPipTime());
        launchState.launchedViaDockGesture = mLaunchedWhileDocking;
        launchState.launchedViaDragGesture = mDraggingInRecents;
        launchState.launchedToTaskId = runningTaskId;
        launchState.launchedWithAltTab = mTriggeredFromAltTab;

        // Preload the icon (this will be a null-op if we have preloaded the icon already in
        // preloadRecents())
        preloadIcon(runningTaskId);

        // Update the header bar if necessary
        Rect windowOverrideRect = getWindowRectOverride(growTarget);
        updateHeaderBarLayout(stack, windowOverrideRect);

        // Prepare the dummy stack for the transition
        TaskStackLayoutAlgorithm.VisibilityReport stackVr;
        synchronized (mDummyStackView) {
            stackVr = mDummyStackView.computeStackVisibilityReport();
        }

        // Update the remaining launch state
        launchState.launchedNumVisibleTasks = stackVr.numVisibleTasks;
        launchState.launchedNumVisibleThumbnails = stackVr.numVisibleThumbnails;

        if (!animate) {
            startRecentsActivity(ActivityOptions.makeCustomAnimation(mContext, -1, -1));
            return;
        }

        ActivityOptions opts;
        if (isBlacklisted) {
            opts = getUnknownTransitionActivityOptions();
        } else if (useThumbnailTransition) {
            // Try starting with a thumbnail transition
            opts = getThumbnailTransitionActivityOptions(runningTask, windowOverrideRect);
        } else {
            // If there is no thumbnail transition, but is launching from home into recents, then
            // use a quick home transition
            opts = hasRecentTasks
                ? getHomeTransitionActivityOptions()
                : getUnknownTransitionActivityOptions();
        }
        startRecentsActivity(opts);
        mLastToggleTime = SystemClock.elapsedRealtime();
    }

    private Rect getWindowRectOverride(int growTarget) {
        if (growTarget == DividerView.INVALID_RECENTS_GROW_TARGET) {
            return null;
        }
        Rect result = new Rect();
        Rect displayRect = Recents.getSystemServices().getDisplayRect();
        DockedDividerUtils.calculateBoundsForPosition(growTarget, WindowManager.DOCKED_BOTTOM,
                result, displayRect.width(), displayRect.height(),
                Recents.getSystemServices().getDockedDividerSize(mContext));
        return result;
    }

    /**
     * Starts the recents activity.
     */
    private void startRecentsActivity(ActivityOptions opts) {
        Intent intent = new Intent();
        intent.setClassName(RECENTS_PACKAGE, RECENTS_ACTIVITY);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);

        HidePipMenuEvent hideMenuEvent = new HidePipMenuEvent();
        hideMenuEvent.addPostAnimationCallback(() -> {
            if (opts != null) {
                mContext.startActivityAsUser(intent, opts.toBundle(), UserHandle.CURRENT);
            } else {
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            }
            EventBus.getDefault().send(new RecentsActivityStartingEvent());
        });
        EventBus.getDefault().send(hideMenuEvent);
    }

    /**** OnAnimationFinishedListener Implementation ****/

    @Override
    public void onAnimationFinished() {
        EventBus.getDefault().post(new EnterRecentsWindowLastAnimationFrameEvent());
    }
}
