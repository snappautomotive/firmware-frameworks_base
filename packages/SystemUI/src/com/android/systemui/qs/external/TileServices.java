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
 * limitations under the License
 */
package com.android.systemui.qs.external;

import static com.android.systemui.Flags.qsCustomTileClickGuaranteedBugFix;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.Tile;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArrayMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.pipeline.data.repository.CustomTileAddedRepository;
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Runs the day-to-day operations of which tiles should be bound and when.
 */
@SysUISingleton
public class TileServices extends IQSService.Stub {
    static final int DEFAULT_MAX_BOUND = 3;
    static final int REDUCED_MAX_BOUND = 1;
    private static final String TAG = "TileServices";

    private final ArrayMap<CustomTileInterface, TileServiceManager> mServices = new ArrayMap<>();
    private final SparseArrayMap<ComponentName, CustomTileInterface> mTiles =
            new SparseArrayMap<>();
    private final ArrayMap<IBinder, CustomTileInterface> mTokenMap = new ArrayMap<>();
    private final Context mContext;
    private final Handler mMainHandler;
    private final Provider<Handler> mHandlerProvider;
    private final QSHost mHost;
    private final KeyguardStateController mKeyguardStateController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CommandQueue mCommandQueue;
    private final UserTracker mUserTracker;
    private final StatusBarIconController mStatusBarIconController;
    private final PanelInteractor mPanelInteractor;
    private final TileLifecycleManager.Factory mTileLifecycleManagerFactory;
    private final CustomTileAddedRepository mCustomTileAddedRepository;
    private final DelayableExecutor mBackgroundExecutor;

    private int mMaxBound = DEFAULT_MAX_BOUND;

    @Inject
    public TileServices(
            QSHost host,
            @Main Provider<Handler> handlerProvider,
            BroadcastDispatcher broadcastDispatcher,
            UserTracker userTracker,
            KeyguardStateController keyguardStateController,
            CommandQueue commandQueue,
            StatusBarIconController statusBarIconController,
            PanelInteractor panelInteractor,
            TileLifecycleManager.Factory tileLifecycleManagerFactory,
            CustomTileAddedRepository customTileAddedRepository,
            @Background DelayableExecutor backgroundExecutor) {
        mHost = host;
        mKeyguardStateController = keyguardStateController;
        mContext = mHost.getContext();
        mBroadcastDispatcher = broadcastDispatcher;
        mHandlerProvider = handlerProvider;
        mMainHandler = mHandlerProvider.get();
        mUserTracker = userTracker;
        mCommandQueue = commandQueue;
        mStatusBarIconController = statusBarIconController;
        mCommandQueue.addCallback(mRequestListeningCallback);
        mPanelInteractor = panelInteractor;
        mTileLifecycleManagerFactory = tileLifecycleManagerFactory;
        mCustomTileAddedRepository = customTileAddedRepository;
        mBackgroundExecutor = backgroundExecutor;
    }

    public Context getContext() {
        return mContext;
    }

    public QSHost getHost() {
        return mHost;
    }

    public TileServiceManager getTileWrapper(CustomTileInterface tile) {
        ComponentName component = tile.getComponent();
        int userId = tile.getUser();
        TileServiceManager service = onCreateTileService(component, mBroadcastDispatcher);
        synchronized (mServices) {
            mServices.put(tile, service);
            mTiles.add(userId, component, tile);
            mTokenMap.put(service.getToken(), tile);
        }
        // Makes sure binding only happens after the maps have been populated
        service.startLifecycleManagerAndAddTile();
        return service;
    }

    protected TileServiceManager onCreateTileService(ComponentName component,
            BroadcastDispatcher broadcastDispatcher) {
        return new TileServiceManager(this, mHandlerProvider.get(), component, mUserTracker,
                mTileLifecycleManagerFactory, mCustomTileAddedRepository);
    }

    public void freeService(CustomTileInterface tile, TileServiceManager service) {
        synchronized (mServices) {
            service.setBindAllowed(false);
            service.handleDestroy();
            mServices.remove(tile);
            mTokenMap.remove(service.getToken());
            mTiles.delete(tile.getUser(), tile.getComponent());
            final String slot = getStatusBarIconSlotName(tile.getComponent());
            mMainHandler.post(() -> mStatusBarIconController.removeIconForTile(slot));
        }
    }

    public void setMemoryPressure(boolean memoryPressure) {
        mMaxBound = memoryPressure ? REDUCED_MAX_BOUND : DEFAULT_MAX_BOUND;
        recalculateBindAllowance();
    }

    public void recalculateBindAllowance() {
        final ArrayList<TileServiceManager> services;
        synchronized (mServices) {
            services = new ArrayList<>(mServices.values());
        }
        final int N = services.size();
        if (N > mMaxBound) {
            long currentTime = System.currentTimeMillis();
            // Precalculate the priority of services for binding.
            for (int i = 0; i < N; i++) {
                services.get(i).calculateBindPriority(currentTime);
            }
            // Sort them so we can bind the most important first.
            Collections.sort(services, SERVICE_SORT);
        }
        int i;
        // Allow mMaxBound items to bind.
        for (i = 0; i < mMaxBound && i < N; i++) {
            services.get(i).setBindAllowed(true);
        }
        // The rest aren't allowed to bind for now.
        while (i < N) {
            services.get(i).setBindAllowed(false);
            i++;
        }
    }

    private int verifyCaller(CustomTileInterface tile) {
        try {
            String packageName = tile.getComponent().getPackageName();
            int uid = mContext.getPackageManager().getPackageUidAsUser(packageName,
                    Binder.getCallingUserHandle().getIdentifier());
            if (Binder.getCallingUid() != uid) {
                throw new SecurityException("Component outside caller's uid");
            }
            return uid;
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(e);
        }
    }

    private void requestListening(ComponentName component) {
        synchronized (mServices) {
            int userId = mUserTracker.getUserId();
            CustomTileInterface customTile = getTileForUserAndComponent(userId, component);
            if (customTile == null) {
                Log.d(TAG, "Couldn't find tile for " + component + "(" + userId + ")");
                return;
            }
            TileServiceManager service = mServices.get(customTile);
            if (service == null) {
                Log.e(
                        TAG,
                        "No TileServiceManager found in requestListening for tile "
                                + customTile.getTileSpec());
                return;
            }
            if (!service.isActiveTile()) {
                return;
            }
            service.setBindRequested(true);
            if (qsCustomTileClickGuaranteedBugFix()) {
                service.onStartListeningFromRequest();
            } else {
                try {
                    service.getTileService().onStartListening();
                } catch (RemoteException e) {
                }
            }
        }
    }

    @Override
    public void updateQsTile(Tile tile, IBinder token) {
        CustomTileInterface customTile = getTileForToken(token);
        if (customTile != null) {
            int uid = verifyCaller(customTile);
            synchronized (mServices) {
                final TileServiceManager tileServiceManager = mServices.get(customTile);
                if (tileServiceManager == null || !tileServiceManager.isLifecycleStarted()) {
                    Log.e(TAG, "TileServiceManager not started for " + customTile.getComponent(),
                            new IllegalStateException());
                    return;
                }
                tileServiceManager.clearPendingBind();
                tileServiceManager.setLastUpdate(System.currentTimeMillis());
            }
            customTile.updateTileState(tile, uid);
            customTile.refreshState();
        }
    }

    @Override
    public void onStartSuccessful(IBinder token) {
        CustomTileInterface customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            synchronized (mServices) {
                final TileServiceManager tileServiceManager = mServices.get(customTile);
                // This should not happen as the TileServiceManager should have been started for the
                // first bind to happen.
                if (tileServiceManager == null || !tileServiceManager.isLifecycleStarted()) {
                    Log.e(TAG, "TileServiceManager not started for " + customTile.getComponent(),
                            new IllegalStateException());
                    return;
                }
                tileServiceManager.clearPendingBind();
            }
            customTile.refreshState();
        }
    }

    @Override
    public void onShowDialog(IBinder token) {
        CustomTileInterface customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            customTile.onDialogShown();
            mPanelInteractor.forceCollapsePanels();
            Objects.requireNonNull(mServices.get(customTile)).setShowingDialog(true);
        }
    }

    @Override
    public void onDialogHidden(IBinder token) {
        CustomTileInterface customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            Objects.requireNonNull(mServices.get(customTile)).setShowingDialog(false);
            customTile.onDialogHidden();
        }
    }

    @Override
    public void onStartActivity(IBinder token) {
        CustomTileInterface customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            mPanelInteractor.forceCollapsePanels();
        }
    }

    @Override
    public void startActivity(IBinder token, PendingIntent pendingIntent) {
        startActivity(getTileForToken(token), pendingIntent);
    }

    @VisibleForTesting
    protected void startActivity(CustomTileInterface customTile, PendingIntent pendingIntent) {
        if (customTile != null) {
            verifyCaller(customTile);
            customTile.startActivityAndCollapse(pendingIntent);
        }
    }

    @Override
    public void updateStatusIcon(IBinder token, Icon icon, String contentDescription) {
        CustomTileInterface customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            try {
                ComponentName componentName = customTile.getComponent();
                String packageName = componentName.getPackageName();
                UserHandle userHandle = getCallingUserHandle();
                PackageInfo info = mContext.getPackageManager().getPackageInfoAsUser(packageName, 0,
                        userHandle.getIdentifier());
                if (info.applicationInfo.isSystemApp()) {
                    final StatusBarIcon statusIcon = icon != null
                            ? new StatusBarIcon(userHandle, packageName, icon, 0, 0,
                            contentDescription, StatusBarIcon.Type.SystemIcon)
                            : null;
                    final String slot = getStatusBarIconSlotName(componentName);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mStatusBarIconController.setIconFromTile(slot, statusIcon);
                        }
                    });
                }
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
    }

    @Nullable
    @Override
    public Tile getTile(IBinder token) {
        CustomTileInterface customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            return customTile.getQsTile();
        }
        Log.e(TAG, "Tile for token " + token + "not found. "
                + "Tiles in map: " + availableTileComponents());
        return null;
    }

    private String availableTileComponents() {
        StringBuilder sb = new StringBuilder("[");
        synchronized (mServices) {
            mTokenMap.forEach((iBinder, customTile) ->
                    sb.append(iBinder.toString())
                            .append(":")
                            .append(customTile.getComponent().flattenToShortString())
                            .append(":")
                            .append(customTile.getUser())
                            .append(","));
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public void startUnlockAndRun(IBinder token) {
        CustomTileInterface customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            customTile.startUnlockAndRun();
        }
    }

    @Override
    public boolean isLocked() {
        return mKeyguardStateController.isShowing();
    }

    @Override
    public boolean isSecure() {
        return mKeyguardStateController.isMethodSecure() && mKeyguardStateController.isShowing();
    }

    @Nullable
    public CustomTileInterface getTileForToken(IBinder token) {
        synchronized (mServices) {
            return mTokenMap.get(token);
        }
    }

    @Nullable
    private CustomTileInterface getTileForUserAndComponent(int userId, ComponentName component) {
        synchronized (mServices) {
            return mTiles.get(userId, component);
        }
    }

    public void destroy() {
        synchronized (mServices) {
            mServices.values().forEach(service -> service.handleDestroy());
        }
        mCommandQueue.removeCallback(mRequestListeningCallback);
    }

    /** Returns the slot name that should be used when adding or removing status bar icons. */
    private String getStatusBarIconSlotName(ComponentName componentName) {
        return componentName.getClassName();
    }


    private final CommandQueue.Callbacks mRequestListeningCallback = new CommandQueue.Callbacks() {
        @Override
        public void requestTileServiceListeningState(@NonNull ComponentName componentName) {
            mMainHandler.post(() -> requestListening(componentName));
        }
    };

    private static final Comparator<TileServiceManager> SERVICE_SORT =
            (left, right) -> -Integer.compare(left.getBindPriority(), right.getBindPriority());

}
