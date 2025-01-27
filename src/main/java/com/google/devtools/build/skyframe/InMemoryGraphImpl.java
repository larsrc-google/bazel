// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.supplier.InterruptibleSupplier;
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.ForOverride;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * An in-memory graph implementation. All operations are thread-safe with ConcurrentMap semantics.
 * Also see {@link NodeEntry}.
 *
 * <p>This class is public only for use in alternative graph implementations.
 */
public class InMemoryGraphImpl implements InMemoryGraph {

  private static final int PARALLELISM_THRESHOLD = 1024;

  protected final ConcurrentHashMap<SkyKey, InMemoryNodeEntry> nodeMap;
  private final NodeBatch getBatch;
  private final NodeBatch createIfAbsentBatch;

  // TODO(b/250641010): Remove this class member along with the startup flag.
  private final boolean usePooledSkyKeyInterning;

  InMemoryGraphImpl() {
    this(/* initialCapacity= */ 1 << 10);
  }

  @VisibleForTesting
  public InMemoryGraphImpl(boolean usePooledSkyKeyInterning) {
    this(/* initialCapacity= */ 1 << 10, usePooledSkyKeyInterning);
  }

  protected InMemoryGraphImpl(int initialCapacity) {
    this(initialCapacity, UsePooledSkyKeyInterningFlag.usePooledSkyKeyInterningFlag());
  }

  private InMemoryGraphImpl(int initialCapacity, boolean usePooledSkyKeyInterning) {
    this.nodeMap = new ConcurrentHashMap<>(initialCapacity);
    this.getBatch = nodeMap::get;
    this.createIfAbsentBatch = this::createIfAbsent;
    this.usePooledSkyKeyInterning = usePooledSkyKeyInterning;
    if (usePooledSkyKeyInterning) {
      SkyKeyInterner.setGlobalPool(this);
    }
  }

  @Override
  public void remove(SkyKey skyKey) {
    weakIntern(skyKey);
    nodeMap.remove(skyKey);
  }

  private void weakIntern(SkyKey skyKey) {
    if (!usePooledSkyKeyInterning) {
      return;
    }
    SkyKeyInterner<?> interner = skyKey.getSkyKeyInterner();
    if (interner != null) {
      interner.weakIntern(skyKey);
    }
  }

  @Override
  public NodeEntry get(@Nullable SkyKey requestor, Reason reason, SkyKey skyKey) {
    return nodeMap.get(skyKey);
  }

  @Override
  public NodeBatch getBatch(
      @Nullable SkyKey requestor, Reason reason, Iterable<? extends SkyKey> keys) {
    return getBatch;
  }

  @Override
  public InterruptibleSupplier<NodeBatch> getBatchAsync(
      @Nullable SkyKey requestor, Reason reason, Iterable<? extends SkyKey> keys) {
    return () -> getBatch;
  }

  @Override
  public Map<SkyKey, NodeEntry> getBatchMap(
      SkyKey requestor, Reason reason, Iterable<? extends SkyKey> keys) {
    // Use a HashMap, not an ImmutableMap.Builder, because we have not yet deduplicated these keys
    // and ImmutableMap.Builder does not tolerate duplicates. The map will be thrown away shortly.
    HashMap<SkyKey, NodeEntry> result = new HashMap<>();
    for (SkyKey key : keys) {
      NodeEntry entry = get(null, Reason.OTHER, key);
      if (entry != null) {
        result.put(key, entry);
      }
    }
    return result;
  }

  @ForOverride
  protected InMemoryNodeEntry newNodeEntry(SkyKey key) {
    return new InMemoryNodeEntry(key);
  }

  /**
   * This is used to call newNodeEntry() from within computeIfAbsent. Instantiated here to avoid
   * lambda instantiation overhead.
   */
  @SuppressWarnings("UnnecessaryLambda")
  private final Function<SkyKey, InMemoryNodeEntry> newNodeEntryFunction = this::newNodeEntry;

  @Override
  @CanIgnoreReturnValue
  public NodeBatch createIfAbsentBatch(
      @Nullable SkyKey requestor, Reason reason, Iterable<? extends SkyKey> keys) {
    for (SkyKey key : keys) {
      createIfAbsent(key);
    }
    return createIfAbsentBatch;
  }

  @CanIgnoreReturnValue
  private InMemoryNodeEntry createIfAbsent(SkyKey skyKey) {
    InMemoryNodeEntry inMemoryNodeEntry = nodeMap.computeIfAbsent(skyKey, newNodeEntryFunction);
    if (usePooledSkyKeyInterning) {
      SkyKeyInterner<?> interner = skyKey.getSkyKeyInterner();
      if (interner != null) {
        interner.removeWeak(skyKey);
      }
    }
    return inMemoryNodeEntry;
  }

  @Override
  public DepsReport analyzeDepsDoneness(SkyKey parent, Collection<SkyKey> deps) {
    return DepsReport.NO_INFORMATION;
  }

  @Override
  public int valuesSize() {
    return nodeMap.size();
  }

  @Override
  public Map<SkyKey, SkyValue> getValues() {
    return Collections.unmodifiableMap(Maps.transformValues(nodeMap, InMemoryNodeEntry::toValue));
  }

  @Override
  public Map<SkyKey, SkyValue> getDoneValues() {
    return Collections.unmodifiableMap(
        Maps.filterValues(
            Maps.transformValues(nodeMap, entry -> entry.isDone() ? entry.getValue() : null),
            Objects::nonNull));
  }

  @Override
  public Collection<InMemoryNodeEntry> getAllNodeEntries() {
    return Collections.unmodifiableCollection(nodeMap.values());
  }

  @Override
  public void parallelForEach(Consumer<InMemoryNodeEntry> consumer) {
    nodeMap.forEachValue(PARALLELISM_THRESHOLD, consumer);
  }

  @Nullable
  @Override
  public SkyKey canonicalize(SkyKey key) {
    InMemoryNodeEntry node = nodeMap.get(key);
    return node != null ? node.getKey() : null;
  }

  /**
   * Re-interns {@link SkyKey} instances that use {@link SkyKeyInterner} in node map back to the
   * {@link SkyKeyInterner}'s weak interner.
   *
   * <p>Also uninstalls this {@link InMemoryGraphImpl} instance from being {@link SkyKeyInterner}'s
   * static global pool.
   */
  @Override
  public void cleanupPool() {
    if (!usePooledSkyKeyInterning) {
      // No clean up is needed when UseSkyKeyInternerFlag startup flag is off.
      return;
    }
    try (AutoProfiler ignored =
        GoogleAutoProfilerUtils.logged(
            "re-interning Skykeys back to bazel regular weak interner from SkyKeyPool",
            Duration.ofMillis(2L))) {
      parallelForEach(e -> weakIntern(e.getKey()));
    }
    SkyKeyInterner.setGlobalPool(null);
  }

  static final class EdgelessInMemoryGraphImpl extends InMemoryGraphImpl {

    public EdgelessInMemoryGraphImpl() {
      super();
    }

    @VisibleForTesting
    public EdgelessInMemoryGraphImpl(boolean usePooledSkyKeyInterning) {
      super(usePooledSkyKeyInterning);
    }

    @Override
    protected InMemoryNodeEntry newNodeEntry(SkyKey key) {
      return new EdgelessInMemoryNodeEntry(key);
    }
  }
}
