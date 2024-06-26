/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup;

import static com.android.server.backup.FullBackupJob.getJobIdForUserId;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.annotation.UserIdInt;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import com.android.server.testing.shadows.ShadowSystemServiceRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowJobScheduler;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowJobScheduler.class, ShadowSystemServiceRegistry.class})
@Presubmit
public class FullBackupJobTest {
    private Context mContext;
    private BackupManagerConstants mConstants;
    private ShadowJobScheduler mShadowJobScheduler;

    @Mock
    private UserBackupManagerService mUserBackupManagerService;

    @UserIdInt private int mUserOneId;
    @UserIdInt private int mUserTwoId;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mConstants = new BackupManagerConstants(Handler.getMain(), mContext.getContentResolver());
        mConstants.start();
        when(mUserBackupManagerService.getConstants()).thenReturn(mConstants);
        when(mUserBackupManagerService.isFrameworkSchedulingEnabled()).thenReturn(true);

        mShadowJobScheduler = Shadows.shadowOf(mContext.getSystemService(JobScheduler.class));

        mUserOneId = UserHandle.USER_SYSTEM;
        mUserTwoId = mUserOneId + 1;
    }

    @After
    public void tearDown() throws Exception {
        mConstants.stop();
        FullBackupJob.cancel(mUserOneId, mContext);
        FullBackupJob.cancel(mUserTwoId, mContext);
    }

    @Test
    public void testSchedule_afterScheduling_jobExists() {
        FullBackupJob.schedule(mUserOneId, mContext, 0, mUserBackupManagerService);
        FullBackupJob.schedule(mUserTwoId, mContext, 0, mUserBackupManagerService);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNotNull();
        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserTwoId))).isNotNull();
    }

    @Test
    public void testSchedule_notWatch_requiresDeviceIdle() {
        shadowOf(mContext.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_WATCH, false);
        FullBackupJob.schedule(mUserOneId, mContext, 0, mUserBackupManagerService);

        JobInfo pendingJob = mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId));
        assertThat(pendingJob.isRequireDeviceIdle()).isTrue();
    }

    @Test
    public void testSchedule_isWatch_doesNotRequireDeviceIdle() {
        shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_WATCH, true);
        FullBackupJob.schedule(mUserOneId, mContext, 0, mUserBackupManagerService);

        JobInfo pendingJob = mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId));
        assertThat(pendingJob.isRequireDeviceIdle()).isFalse();
    }

    @Test
    public void testCancel_afterCancelling_jobDoesntExist() {
        FullBackupJob.schedule(mUserOneId, mContext, 0, mUserBackupManagerService);
        FullBackupJob.schedule(mUserTwoId, mContext, 0, mUserBackupManagerService);
        FullBackupJob.cancel(mUserOneId, mContext);
        FullBackupJob.cancel(mUserTwoId, mContext);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNull();
        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserTwoId))).isNull();
    }

    @Test
    public void testSchedule_isNoopIfDisabled() {
        when(mUserBackupManagerService.isFrameworkSchedulingEnabled()).thenReturn(false);
        FullBackupJob.schedule(mUserOneId, mContext, 0, mUserBackupManagerService);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNull();
    }

    @Test
    public void testSchedule_schedulesJobIfEnabled() {
        when(mUserBackupManagerService.isFrameworkSchedulingEnabled()).thenReturn(true);
        FullBackupJob.schedule(mUserOneId, mContext, 0, mUserBackupManagerService);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNotNull();
    }
//
    @Test
    public void testSchedule_onlySchedulesForRequestedUser() {
        FullBackupJob.schedule(mUserOneId, mContext, 0, mUserBackupManagerService);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNotNull();
        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserTwoId))).isNull();
    }
//
    @Test
    public void testCancel_onlyCancelsForRequestedUser() {
        FullBackupJob.schedule(mUserOneId, mContext, 0, mUserBackupManagerService);
        FullBackupJob.schedule(mUserTwoId, mContext, 0, mUserBackupManagerService);
        FullBackupJob.cancel(mUserOneId, mContext);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNull();
        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserTwoId))).isNotNull();
    }
}
