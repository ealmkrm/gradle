/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.internal.service.scopes;

import com.google.common.collect.ImmutableList;
import org.gradle.StartParameter;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.file.BaseDirFileResolver;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.initialization.RootBuildLifecycleListener;
import org.gradle.internal.file.FileType;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.internal.vfs.WatchingVirtualFileSystem;
import org.gradle.util.IncubationLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class VirtualFileSystemBuildLifecycleListener implements RootBuildLifecycleListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualFileSystemBuildLifecycleListener.class);

    public interface StartParameterSwitch {
        boolean isEnabled(StartParameter startParameter);
    }

    public interface StartParameterValue {
        @Nullable
        String getValue(StartParameter startParameter);
    }

    private final WatchingVirtualFileSystem virtualFileSystem;
    private final StartParameterSwitch vfsRetention;
    private final StartParameterSwitch dropVfs;
    private final StartParameterValue changedPathsSinceLastBuild;

    public VirtualFileSystemBuildLifecycleListener(
        WatchingVirtualFileSystem virtualFileSystem,
        StartParameterSwitch vfsRetention,
        StartParameterSwitch dropVfs,
        StartParameterValue changedPathsSinceLastBuild
    ) {
        this.virtualFileSystem = virtualFileSystem;
        this.vfsRetention = vfsRetention;
        this.dropVfs = dropVfs;
        this.changedPathsSinceLastBuild = changedPathsSinceLastBuild;
    }

    @Override
    public void afterStart(GradleInternal gradle) {
        StartParameter startParameter = gradle.getStartParameter();
        if (vfsRetention.isEnabled(startParameter)) {
            IncubationLogger.incubatingFeatureUsed("Virtual file system retention");
            FileResolver fileResolver = new BaseDirFileResolver(startParameter.getCurrentDir(), () -> {
                throw new UnsupportedOperationException();
            });
            if (dropVfs.isEnabled(startParameter)) {
                virtualFileSystem.invalidateAll();
            } else {
                List<File> changedPathsSinceLastBuild = getChangedPathsSinceLastBuild(fileResolver, this.changedPathsSinceLastBuild.getValue(startParameter));
                for (File changedPathSinceLastBuild : changedPathsSinceLastBuild) {
                    LOGGER.warn("Marking as changed since last build: {}", changedPathSinceLastBuild);
                }
                virtualFileSystem.update(
                    changedPathsSinceLastBuild
                        .stream()
                        .map(File::getAbsolutePath)
                        .collect(Collectors.toList()),
                    () -> {}
                );
            }
            WatchingVirtualFileSystem.VirtualFileSystemStatistics statistics = virtualFileSystem.getStatistics();
            LOGGER.warn(
                "Virtual file system retained information about {} files, {} directories and {} missing files since last build",
                statistics.getRetained(FileType.RegularFile),
                statistics.getRetained(FileType.Directory),
                statistics.getRetained(FileType.Missing)
            );
        } else {
            virtualFileSystem.stopWatching();
            virtualFileSystem.invalidateAll();
        }
    }

    @Override
    public void beforeComplete(GradleInternal gradle) {
        if (vfsRetention.isEnabled(gradle.getStartParameter())) {
            virtualFileSystem.stopWatching();
            virtualFileSystem.startWatching(Collections.singleton(gradle.getRootProject().getProjectDir()));
            WatchingVirtualFileSystem.VirtualFileSystemStatistics statistics = virtualFileSystem.getStatistics();
            LOGGER.warn(
                "Virtual file system retains information about {} files, {} directories and {} missing files till next build",
                statistics.getRetained(FileType.RegularFile),
                statistics.getRetained(FileType.Directory),
                statistics.getRetained(FileType.Missing)
            );
        } else {
            virtualFileSystem.invalidateAll();
        }
    }

    public List<File> getChangedPathsSinceLastBuild(PathToFileResolver resolver, @Nullable String changeList) {
        if (changeList == null) {
            return ImmutableList.of();
        }
        return Stream.of(changeList.split(","))
            .filter(path -> !path.isEmpty())
            .map(resolver::resolve)
            .collect(Collectors.toList());
    }
}
