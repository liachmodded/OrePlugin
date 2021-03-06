package org.spongepowered.ore.client;

import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createFile;
import static java.nio.file.Files.delete;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.move;
import static org.spongepowered.ore.client.Routes.PROJECT;
import static org.spongepowered.ore.client.Routes.PROJECT_LIST;
import static org.spongepowered.ore.client.Routes.USER;
import static org.spongepowered.ore.client.Routes.VERSION;

import com.google.common.reflect.TypeToken;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.spongepowered.api.Game;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.plugin.PluginManager;
import org.spongepowered.api.util.file.DeleteFileVisitor;
import org.spongepowered.ore.SpongeOrePlugin;
import org.spongepowered.ore.client.exception.*;
import org.spongepowered.ore.client.http.OreConnection;
import org.spongepowered.ore.client.http.PluginDownload;
import org.spongepowered.ore.client.model.project.Dependency;
import org.spongepowered.ore.client.model.project.Project;
import org.spongepowered.ore.client.model.user.User;
import org.spongepowered.ore.client.model.project.Version;
import org.spongepowered.plugin.meta.PluginMetadata;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * An implementation of {@link OreClient} built around the {@link Sponge}
 * platform.
 */
public final class SpongeOreClient implements OreClient {

    private final Game game;
    private final PluginManager pluginManager;
    private final URL rootUrl;
    private final Path modsDir, updatesDir, downloadsDir;
    private final Map<String, Installation> newInstalls = new HashMap<>();
    private final Map<String, Installation> updatesToInstall = new HashMap<>();
    private final Set<PluginContainer> toRemove = new HashSet<>();
    private final Set<String> ignoredPlugins;
    private Messenger messenger;

    public SpongeOreClient(URL rootUrl, Path modsDir, Path updatesDir, Path downloadsDir,
        Set<String> ignoredPlugins, Game game) {
        this.rootUrl = rootUrl;
        this.modsDir = modsDir;
        this.updatesDir = updatesDir;
        this.downloadsDir = downloadsDir;
        this.ignoredPlugins = ignoredPlugins;
        this.game = game;
        this.pluginManager = game.getPluginManager();
    }

    @Override
    public void setMessenger(Messenger messenger) {
        this.messenger = messenger;
    }

    @Override
    public URL getRootUrl() {
        return this.rootUrl;
    }

    @Override
    public boolean isInstalled(String id) {
        // Returns true if the plugin is loaded and is not going to be
        // uninstalled or if the plugin is unloaded and is going to be
        // installed
        boolean loaded = this.pluginManager.isLoaded(id);
        boolean removalPending = this.toRemove.stream()
            .filter(plugin -> plugin.getId().equals(id))
            .findAny()
            .isPresent();
        return (loaded && !removalPending) || (!loaded && this.newInstalls.containsKey(id));
    }

    @Override
    public Optional<Installation> getInstallation(String id) {
        if (!isInstalled(id))
            return Optional.empty();

        Installation update = this.updatesToInstall.get(id);
        if (update != null)
            return Optional.of(update);

        Installation install = this.newInstalls.get(id);
        if (install != null)
            return Optional.of(install);

        return this.pluginManager.getPlugin(id).map(Installation::fromContainer);
    }

    @Override
    public void downloadPlugin(String id, String version) throws IOException, PluginNotFoundException {
        this.downloadPlugin(id, version, this.downloadsDir, null);
    }

    @Override
    public void installPlugin(String id, String version, boolean installDependencies, boolean ignorePlatformVersion)
        throws IOException, PluginAlreadyInstalledException, PluginNotFoundException {
        checkNotInstalled(id);

        if (installDependencies) {
            // Get intended version's dependencies
            sendMessage("Finding dependencies...");

            String actualVersion = version;
            if (version.equals(VERSION_RECOMMENDED)) {
                actualVersion = getProject(id).orElseThrow(() -> new PluginNotFoundException(id))
                        .getRecommendedVersion().getName();
            }

            Version toInstall = getModel(Version.class, VERSION, id, actualVersion)
                .orElseThrow(() -> new PluginNotFoundException(id));

            List<Dependency> dependencies = toInstall.getDependencies();

            // Check API version
            if (!ignorePlatformVersion) {
                dependencies.stream().filter(d -> d.getPluginId().equals(Platform.API_ID)).findAny().ifPresent(api -> {
                    String requiredApiVersion = api.getVersion();
                    String currentApiVersion = this.game.getPlatform().getApi().getVersion()
                            .orElseThrow(() -> new IllegalStateException("no API version found?"));
                    int firstStopIndex = requiredApiVersion.indexOf(".");
                    int currentStopIndex = currentApiVersion.indexOf(".");
                    if (currentStopIndex == -1) {
                        throw new IllegalStateException(
                                "server running an implementation with an invalid version string? ("
                                        + currentApiVersion + ")");
                    }

                    if (firstStopIndex == -1) {
                        // The dependency on Ore supplied an invalid API version
                        sendMessage("Warning: Plugin declared a dependency to a malformed API version string! ("
                                + requiredApiVersion + ")");
                        return;
                    }

                    int requiredMajor = -1;
                    int currentMajor;
                    try {
                        requiredMajor = Integer.parseInt(requiredApiVersion.substring(0, firstStopIndex));
                    } catch (NumberFormatException e) {
                        sendMessage("Warning: Plugin declared a dependency to a API version with a non-integer major "
                                + "version! (" + requiredApiVersion + ")");
                    }

                    if (requiredMajor != -1) {
                        try {
                            currentMajor = Integer.parseInt(currentApiVersion.substring(0, currentStopIndex));
                        } catch (NumberFormatException e) {
                            throw new IllegalStateException("server running an implementation with a version string "
                                    + "with a non-integer major version? (" + currentApiVersion + ")");
                        }

                        if (currentMajor != requiredMajor)
                            throw new UnsupportedPlatformVersion(requiredApiVersion, currentApiVersion);
                    }
                });
            }

            dependencies = dependencies.stream()
                    .filter(d -> !d.getPluginId().equals(Platform.API_ID))
                    .collect(Collectors.toCollection(ArrayList::new));

            for (Dependency depend : dependencies) {
                String dependId = depend.getPluginId();
                String dependVersion = depend.getVersion();
                sendMessage("Installing " + dependId + " v" + dependVersion + "...");
                try {
                    installPlugin(dependId, dependVersion, true);
                } catch (PluginAlreadyInstalledException ignored) {
                    // Warn if running a different version then what the dependency suggests
                    String installedVersion = getInstallation(dependId).get().getVersion();
                    if (!installedVersion.equals(dependVersion))
                        sendMessage("Warning: This plugin depends on " + dependId + " v" + dependVersion + ", but you"
                            + " already have v" + installedVersion + " installed. Your plugin may not run or "
                            + "run as expected without it.");
                } catch (PluginNotFoundException e) {
                    sendMessage("Warning: Could not resolve dependency " + dependId + " v" + dependVersion + ", your "
                        + "plugin may not run or run as expected without it.");
                }
            }
        }

        // A plugin can be uninstalled but still loaded, download to updates
        // dir if this is the case
        Path target;
        if (this.pluginManager.isLoaded(id))
            this.downloadPlugin(id, version, this.updatesDir, this.updatesToInstall);
        else
            this.downloadPlugin(id, version, this.modsDir, this.newInstalls);

        // Remove from uninstallation list if present
        this.toRemove.removeIf(plugin -> plugin.getId().equals(id));
    }

    @Override
    public void uninstallPlugin(String id) throws IOException, PluginNotInstalledException {
        checkInstalled(id);

        // Add to removal set if loaded, delete file otherwise
        if (this.pluginManager.isLoaded(id))
            this.toRemove.add(this.pluginManager.getPlugin(id).get());
        else
            // Delete pending installs
            delete(this.newInstalls.remove(id).getPath());

        // Delete pending updates
        if (this.updatesToInstall.containsKey(id))
            delete(this.updatesToInstall.remove(id).getPath());
    }

    @Override
    public boolean isUpdateAvailable(String id) throws IOException, PluginNotInstalledException {
        checkInstalled(id);

        // Make sure project is on Ore
        Optional<Project> projectOpt = getProject(id);
        if (!projectOpt.isPresent())
            return false;

        // Compare Ore version to installed version
        String currentVersion = getInstallation(id).get().getVersion();
        return !currentVersion.equals(VERSION_RECOMMENDED)
            && !currentVersion.equals(projectOpt.get().getRecommendedVersion().getName());
    }

    @Override
    public Map<PluginContainer, String> getAvailableUpdates() throws IOException {
        Map<PluginContainer, String> updates = new HashMap<>();
        for (PluginContainer plugin : this.pluginManager.getPlugins()) {
            String id = plugin.getId();
            if (this.ignoredPlugins.contains(id))
                continue;

            getProject(id).ifPresent(project -> {
                String recommended = project.getRecommendedVersion().getName();
                if (!recommended.equals(plugin.getVersion().orElse(null)))
                    updates.put(plugin, recommended);
            });
        }
        return updates;
    }

    @Override
    public void updatePlugin(String id, String version)
        throws IOException, PluginNotInstalledException, PluginNotFoundException, NoUpdateAvailableException {
        checkInstalled(id);
        if (version.equals(VERSION_RECOMMENDED) && !isUpdateAvailable(id))
            throw new NoUpdateAvailableException(id);
        this.downloadPlugin(id, version, this.updatesDir, this.updatesToInstall);
    }

    @Override
    public boolean hasUninstalledUpdates() {
        return !this.updatesToInstall.isEmpty();
    }

    @Override
    public int getUninstalledUpdates() {
        return this.updatesToInstall.size();
    }

    @Override
    public void installUpdates() throws IOException {
        Map<Path, List<PluginMetadata>> installedMetadata = new PluginMetadataScanner(this.modsDir).scan();
        for (String pluginId : this.updatesToInstall.keySet()) {
            // Delete obsolete version
            for (Path installedPath : installedMetadata.keySet()) {
                boolean match = installedMetadata.get(installedPath).stream()
                    .filter(meta -> meta.getId().equals(pluginId))
                    .findAny()
                    .isPresent();
                if (match) {
                    delete(installedPath);
                    break;
                }
            }

            // Install new update
            Path updatePath = this.updatesToInstall.get(pluginId).getPath();
            Path target = this.modsDir.resolve(updatePath.getFileName());
            String fileName = target.getFileName().toString();
            String name = fileName.substring(0, fileName.lastIndexOf('.'));
            target = findAvailablePath(name, target);

            createDirectories(this.modsDir);
            move(updatePath, target);
        }

        Files.walkFileTree(this.updatesDir, DeleteFileVisitor.INSTANCE);
    }

    @Override
    public List<Project> searchProjects(String query) throws IOException {
        Project[] projects = OreConnection.openWithQuery(this, PROJECT_LIST, "?q=" + query).read(Project[].class);
        return projects != null ? Arrays.asList(projects) : Collections.emptyList();
    }

    @Override
    public boolean hasPendingUninstallations() {
        return !this.toRemove.isEmpty();
    }

    @Override
    public int getPendingUninstallations() {
        return this.toRemove.size();
    }

    @Override
    public void completeUninstallations() throws IOException {
        // Perform uninstalls
        for (PluginContainer plugin : this.toRemove) {
            Optional<Path> pathOpt = plugin.getSource();
            if (pathOpt.isPresent())
                deleteIfExists(pathOpt.get());
        }
    }

    @Override
    public Optional<User> getUser(String username) throws IOException {
        return getModel(User.class, USER, username);
    }

    @Override
    public Optional<Project> getProject(String id) throws IOException {
        return getModel(Project.class, PROJECT, id);
    }

    private <T> Optional<T> getModel(Class<T> clazz, String route, Object... params) throws IOException {
        try {
            return Optional.ofNullable(OreConnection.open(this, route, params).read(clazz));
        } catch (FileNotFoundException e) {
            return Optional.empty();
        }
    }

    private Path downloadPlugin(String id, String version, Path targetDir, Map<String, Installation> downloadMap)
        throws IOException, PluginNotFoundException {
        // Initialize download
        PluginDownload download;
        try {
            download = new PluginDownload(this, id, version).open();
        } catch (FileNotFoundException e) {
            throw new PluginNotFoundException(id);
        }

        // Override already pending installs/updates
        if (downloadMap != null && downloadMap.containsKey(id))
            delete(downloadMap.remove(id).getPath());

        // Copy to target file
        Path target = findAvailablePath(download.getName().get(), targetDir.resolve(download.getFileName().get()));
        createDirectories(target.getParent());
        createFile(target);

        copy(download.getInputStream().get(), target, StandardCopyOption.REPLACE_EXISTING);
        if (downloadMap != null)
            downloadMap.put(id, new Installation(id, version, target));
        return target;
    }

    private Path findAvailablePath(String name, Path target) {
        int conflicts = 0;
        while (exists(target))
            target = target.getParent().resolve(name + " (" + ++conflicts + ").jar");
        return target;
    }

    private void checkNotInstalled(String id) throws PluginAlreadyInstalledException {
        if (isInstalled(id))
            throw new PluginAlreadyInstalledException(id);
    }

    private void checkInstalled(String id) throws PluginNotInstalledException {
        if (!isInstalled(id))
            throw new PluginNotInstalledException(id);
    }

    private void sendMessage(String msg) {
        if (this.messenger != null)
            this.messenger.deliverMessage(msg);
    }

    /**
     * Constructs a new client from for the specified plugin.
     *
     * @param plugin Plugin to create client for
     * @return New client
     */
    public static SpongeOreClient forPlugin(SpongeOrePlugin plugin) {
        ConfigurationNode config = plugin.getConfigRoot();
        final TypeToken<Path> PATH_TOKEN = TypeToken.of(Path.class);
        try {
            return new SpongeOreClient(
                config.getNode("repositoryUrl").getValue(TypeToken.of(URL.class)),
                config.getNode("installationDirectory").getValue(PATH_TOKEN),
                config.getNode("updatesDirectory").getValue(PATH_TOKEN),
                config.getNode("downloadsDirectory").getValue(PATH_TOKEN),
                new HashSet<>(config.getNode("ignoredPlugins").getList(TypeToken.of(String.class))),
                plugin.game);
        } catch (ObjectMappingException e) {
            plugin.log.error("A fatal error occurred while loading your Ore client settings.", e);
            return null;
        }
    }

}
