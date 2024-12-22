/*
 * This file is part of Zapper, licensed under the MIT License.
 *
 *  Copyright (c) Revxrsal <reflxction.github@gmail.com>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
package revxrsal.zapper;

import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import revxrsal.zapper.classloader.URLClassLoaderWrapper;
import revxrsal.zapper.meta.MetaReader;
import revxrsal.zapper.relocation.Relocation;
import revxrsal.zapper.relocation.Relocator;
import revxrsal.zapper.repository.Repository;

import java.io.File;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class DependencyManager implements DependencyScope {

    public static boolean FAILED_TO_DOWNLOAD = false;
    private static final Pattern COLON = Pattern.compile(":");

    private final File directory;
    private final URLClassLoaderWrapper classLoader;

    private final List<Dependency> dependencies = new ArrayList<>();
    private final Set<Repository> repositories = new LinkedHashSet<>();
    private final List<Relocation> relocations = new ArrayList<>();
    private final MetaReader metaReader = MetaReader.create();

    public DependencyManager(@NotNull File directory, @NotNull URLClassLoaderWrapper classLoader) {
        this.directory = directory;
        this.classLoader = classLoader;
        this.repositories.add(Repository.mavenCentral());
    }

    @SneakyThrows
    public void load() {
        try {
            List<Path> paths = new ArrayList<>();
            for (Dependency dep : dependencies) {
                File file = new File(directory, String.format("%s.%s-%s.jar", dep.getGroupId(), dep.getArtifactId(), dep.getVersion()));
                File relocated = new File(directory, String.format("%s.%s-%s-relocated.jar", dep.getGroupId(),
                        dep.getArtifactId(), dep.getVersion()));
                if (hasRelocations() && relocated.exists()) {
                    paths.add(relocated.toPath());
                    continue;
                }
                if (!file.exists()) {
                    boolean succeeded = false;
                    List<String> failedRepos = null;
                    for (Repository repository : repositories) {
                        DependencyDownloadResult result = dep.download(file, repository);
                        if (result.wasSuccessful()) {
                            succeeded = true;
                            break;
                        } else
                            (failedRepos == null ? failedRepos = new ArrayList<>() : failedRepos).add(repository.toString());
                    }
                    if (failedRepos != null && !succeeded) {
                        throw new DependencyDownloadException(dep, "Could not find dependency in any of the following repositories: " + String.join("\n", failedRepos));
                    }
                }
                if (hasRelocations() && !relocated.exists()) {
                    Relocator.relocate(file, relocated, relocations);
                    file.delete(); // no longer need the original dependency
                }
                if (hasRelocations())
                    paths.add(relocated.toPath());
                else
                    paths.add(file.toPath());
            }
            for (Path path : paths)
                classLoader.addURL(path.toUri().toURL());
        } catch (DependencyDownloadException e) {
            if (e.getCause() instanceof UnknownHostException) {
                Bukkit.getLogger().info("[" + metaReader.pluginName() + "] It appears you do not have an internet connection. Please provide an internet connection for once at least.");
                FAILED_TO_DOWNLOAD = true;
            } else throw e;
        }
    }

    @Override
    public void dependency(@NotNull Dependency dependency) {
        dependencies.add(dependency);
    }

    public void dependency(@NotNull String dependency) {
        String[] parts = COLON.split(dependency);
        dependencies.add(new Dependency(parts[0], parts[1], parts[2]));
    }

    public void dependency(@NotNull String groupId, @NotNull String artifactId, @NotNull String version) {
        dependencies.add(new Dependency(groupId, artifactId, version));
    }

    public void relocate(@NotNull Relocation relocation) {
        relocations.add(relocation);
    }

    public void repository(@NotNull Repository repository) {
        repositories.add(repository);
    }

    public boolean hasRelocations() {
        return !relocations.isEmpty();
    }

}
