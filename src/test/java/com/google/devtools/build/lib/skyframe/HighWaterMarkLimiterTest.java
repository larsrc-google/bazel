// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.devtools.build.lib.runtime.MemoryPressureEvent;
import com.google.devtools.build.lib.vfs.SyscallCache;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class HighWaterMarkLimiterTest {

  private static final MemoryPressureEvent MINOR =
      MemoryPressureEvent.newBuilder()
          .setWasManualGc(false)
          .setWasFullGc(false)
          .setTenuredSpaceMaxBytes(100L)
          .setTenuredSpaceUsedBytes(91L)
          .build();
  private static final MemoryPressureEvent FULL =
      MemoryPressureEvent.newBuilder()
          .setWasManualGc(false)
          .setWasFullGc(true)
          .setTenuredSpaceMaxBytes(100L)
          .setTenuredSpaceUsedBytes(91L)
          .build();
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private SkyframeExecutor skyframeExecutor;
  @Mock private SyscallCache syscallCache;

  @Test
  public void testHandle_belowThreshold() {
    HighWaterMarkLimiter underTest =
        new HighWaterMarkLimiter(
            skyframeExecutor,
            syscallCache,
            /* threshold= */ 90,
            /* minorGcDropLimit= */ Integer.MAX_VALUE,
            /* fullGcDropLimit= */ Integer.MAX_VALUE);

    MemoryPressureEvent belowThreshold =
        MemoryPressureEvent.newBuilder()
            .setWasManualGc(false)
            .setWasFullGc(false)
            .setTenuredSpaceMaxBytes(100L)
            .setTenuredSpaceUsedBytes(89L)
            .build();
    underTest.handle(belowThreshold);

    verify(skyframeExecutor, never()).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, never()).clear();
  }

  @Test
  public void testHandle_minorLimitFullUnlimited() {
    HighWaterMarkLimiter underTest =
        new HighWaterMarkLimiter(
            skyframeExecutor,
            syscallCache,
            /* threshold= */ 90,
            /* minorGcDropLimit= */ 1,
            /* fullGcDropLimit= */ Integer.MAX_VALUE);

    verify(skyframeExecutor, never()).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, never()).clear();

    underTest.handle(MINOR);

    verify(skyframeExecutor, times(1)).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, times(1)).clear();

    underTest.handle(MINOR);

    verify(skyframeExecutor, times(1)).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, times(1)).clear();

    underTest.handle(FULL);

    verify(skyframeExecutor, times(2)).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, times(2)).clear();

    underTest.handle(FULL);

    verify(skyframeExecutor, times(3)).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, times(3)).clear();
  }

  @Test
  public void testHandle_minorUnlimitedFullLimit() {
    HighWaterMarkLimiter underTest =
        new HighWaterMarkLimiter(
            skyframeExecutor,
            syscallCache,
            /* threshold= */ 90,
            /* minorGcDropLimit= */ Integer.MAX_VALUE,
            /* fullGcDropLimit= */ 1);

    verify(skyframeExecutor, never()).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, never()).clear();

    underTest.handle(MINOR);

    verify(skyframeExecutor, times(1)).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, times(1)).clear();

    underTest.handle(MINOR);

    verify(skyframeExecutor, times(2)).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, times(2)).clear();

    underTest.handle(FULL);

    verify(skyframeExecutor, times(3)).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, times(3)).clear();

    underTest.handle(FULL);

    verify(skyframeExecutor, times(3)).dropUnnecessaryTemporarySkyframeState();
    verify(syscallCache, times(3)).clear();
  }
}
