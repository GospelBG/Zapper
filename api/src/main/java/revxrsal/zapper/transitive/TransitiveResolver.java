package revxrsal.zapper.transitive;

import lombok.SneakyThrows;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import revxrsal.zapper.Dependency;
import revxrsal.zapper.repository.Repository;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;

import static revxrsal.zapper.repository.Repository.mavenCentral;

/**
 * A utility for fetching transitive dependencies of a {@link revxrsal.zapper.Dependency}.
 * This class allows resolving the transitive dependencies of a given Maven dependency,
 * optionally recursively, and filtering by Maven scopes.
 */
public final class TransitiveResolver {

    private final boolean recursively;
    private final List<MavenScope> scopes;
    private final List<Repository> searchRepositories;

    /**
     * Constructs a new {@link TransitiveResolver}
     *
     * @param recursively        Whether to resolve dependencies recursively (transitive dependencies
     *                           of transitive dependencies).
     * @param scopes             A list of Maven scopes to consider when resolving dependencies.
     * @param searchRepositories A list of repositories to search for the dependencies.
     */
    TransitiveResolver(boolean recursively, List<MavenScope> scopes, List<Repository> searchRepositories) {
        this.recursively = recursively;
        this.scopes = scopes;
        this.searchRepositories = searchRepositories;
    }

    /**
     * Resolves all transitive dependencies of the given dependency.
     * This method searches the provided repositories and recursively resolves the dependencies
     * if required.
     *
     * @param dependency The dependency to resolve transitive dependencies for.
     * @return The list of resolved dependencies.
     * @throws IllegalArgumentException If the POM of the dependency cannot be found in the given repositories.
     */
    @SneakyThrows
    public @NotNull List<Dependency> resolve(@NotNull Dependency dependency) {
        return get(searchRepositories, dependency);
    }

    /**
     * Returns whether the resolver is set to resolve dependencies recursively (i.e.
     * transitive dependencies of transitive dependencies)
     *
     * @return {@code true} if the resolver will resolve dependencies recursively,
     * {@code false} otherwise.
     */
    public boolean isRecursively() {
        return recursively;
    }

    /**
     * Returns the list of the Maven scopes to consider when resolving
     * dependencies.
     *
     * @return The list of scopes.
     */
    public @NotNull @Unmodifiable List<MavenScope> getScopes() {
        return scopes;
    }

    /**
     * Returns the list of the repositories to search for dependencies.
     *
     * @return The list of repositories.
     */
    public @NotNull @Unmodifiable List<Repository> getSearchRepositories() {
        return searchRepositories;
    }

    /**
     * Resolves the transitive dependencies for the given dependency by searching the provided repositories.
     *
     * @param searchRepositories The repositories to search for the dependencies.
     * @param dependency         The dependency for which transitive dependencies are being resolved.
     * @return The list of resolved dependencies.
     * @throws IllegalArgumentException If the POM of the dependency cannot be found in the given repositories.
     */
    @SneakyThrows
    private @NotNull List<Dependency> get(
            @NotNull Iterable<Repository> searchRepositories,
            @NotNull Dependency dependency
    ) {
        for (Repository repository : searchRepositories) {
            try (InputStream stream = repository.resolvePom(dependency).openStream()) {
                return fromPom(dependency, stream);
            } catch (Exception e) {
                if (!(e instanceof FileNotFoundException))
                    throw e;
            }
        }
        throw new IllegalArgumentException("Failed to find the POM of dependency " + dependency.getMavenPath() + " in the following repositories: " + searchRepositories);
    }

    @SneakyThrows
    private NodeList getParentPom(
        @NotNull NodeList pom
    ) {
        Set<Repository> repositories = new LinkedHashSet<>();
        repositories.add(mavenCentral());

        for (int i = 0; i < pom.getLength(); i++) {
            Node node = pom.item(i);
            if (node.getNodeName().equals("repositories")) {
                NodeList repos = node.getChildNodes();
                for (int j = 0; j < repos.getLength(); j++) {
                    Node n = repos.item(j);
                    if (!(n instanceof Element)) continue;
                    Element repoBlock = (Element) n;
                    String url = repoBlock.getElementsByTagName("url").item(0).getTextContent();
                    repositories.add(Repository.maven(url));
                }
            }
        }
        for (int i = 0; i < pom.getLength(); i++) {
            if (pom.item(i).getNodeName().equals("parent")) {
                if (!(pom.item(i) instanceof Element)) continue;
                Element e = (Element) pom.item(i);
                Dependency artifact = new Dependency(
                    e.getElementsByTagName("groupId").item(0).getTextContent(),
                    e.getElementsByTagName("artifactId").item(0).getTextContent(),
                    e.getElementsByTagName("version").item(0).getTextContent());
                
                for (Repository repository : repositories) {
                    try (InputStream s = repository.resolvePom(artifact).openStream()) {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        DocumentBuilder builder = factory.newDocumentBuilder();
                        Document doc = builder.parse(s);
                        NodeList list = doc.getDocumentElement().getChildNodes();

                        return list;
                    } catch (Exception e2) {
                        if (!(e2 instanceof FileNotFoundException))
                            throw e2;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Parses the parent POM file and extracts the version of the dependency.
     *
     * @param dependency The original dependency for which transitive dependencies are being resolved.
     * @param stream     The InputStream of the parent POM file to parse.
     * @return The version of the dependency declared in the parent POM.
     */
    @SneakyThrows
    private @NotNull String getVersionFromParent(
            @NotNull Dependency dependency,
            @NotNull NodeList pom
    ) {        
        Set<Repository> repositories = new LinkedHashSet<>();
        repositories.add(mavenCentral());

        String version = "";

        for (int i = 0; i < pom.getLength(); i++) {
            Node node = pom.item(i);
            if (recursively && node.getNodeName().equals("repositories")) {
                NodeList repos = node.getChildNodes();
                for (int j = 0; j < repos.getLength(); j++) {
                    Node n = repos.item(j);
                    if (!(n instanceof Element)) continue;
                    Element repoBlock = (Element) n;
                    String url = repoBlock.getElementsByTagName("url").item(0).getTextContent();
                    repositories.add(Repository.maven(url));
                }
            }
            if (node.getNodeName().equals("dependencies") || node.getNodeName().equals("dependencyManagement")) {
                NodeList deps = node.getChildNodes();
                for (int j = 0; j < deps.getLength(); j++) {
                    Node n = deps.item(j);
                    if (!(n instanceof Element)) continue;
                    Element dependencyBlock = (Element) n;
                    String groupId = dependencyBlock.getElementsByTagName("groupId").item(0).getTextContent();
                    if (groupId.equals("${project.groupId}")) groupId = dependency.getGroupId();

                    if (groupId.equals(dependency.getGroupId()) && dependencyBlock.getElementsByTagName("artifactId").item(0).getTextContent().equals(dependency.getArtifactId())) {
                        try {
                            version = dependencyBlock.getElementsByTagName("version").item(0).getTextContent();
                            if (version.startsWith("${")) {
                            if (version.equals("${project.version}")) version = dependency.getVersion();
                            else {
                                // Search for property
                                version = getProperty(version.replace("${", "").replace("}", ""), pom);
                            }
                        }
                        } catch (NullPointerException e) {
                            version = getVersionFromParent(dependency, getParentPom(pom));
                        }
                        return version;
                    }
                }
            }
        }
        throw new NullPointerException("Could not find version for artifact " + dependency.getGroupId() + ":" + dependency.getArtifactId());
    }

    @SneakyThrows
    private String getProperty(
        @NotNull String key,
        @NotNull NodeList pom
    ) {
        for (int i = 0; i < pom.getLength(); i++) {
            if (pom.item(i).getNodeName().equals("properties")) {
                NodeList properties = pom.item(i).getChildNodes();
                for (int j = 0; j < properties.getLength(); j++) {
                    if (properties.item(j).getNodeName().equals(key)) {
                        return properties.item(j).getTextContent();
                    }
                }
            }
        }
        // In case of no match, search parent POMs
        NodeList parent = getParentPom(pom);
        if (parent != null) {
            return getProperty(key, getParentPom(pom));
        } else {
            throw new NullPointerException("Unable to find property " + key);
        }
    }

    /**
     * Parses the POM file and extracts the dependencies from it.
     *
     * @param dependency The original dependency for which transitive dependencies are being resolved.
     * @param stream     The InputStream of the parent POM file.
     * @return The list of dependencies extracted from the POM file.
     */
    @SneakyThrows
    private @NotNull List<Dependency> fromPom(
            @NotNull Dependency dependency,
            @NotNull InputStream stream
    ) {
        List<Dependency> dependencies = new ArrayList<>();

        Set<Repository> repositories = new LinkedHashSet<>();
        repositories.add(mavenCentral());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(stream);
        NodeList list = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (recursively && node.getNodeName().equals("repositories")) {
                NodeList repos = node.getChildNodes();
                for (int j = 0; j < repos.getLength(); j++) {
                    Node n = repos.item(j);
                    if (!(n instanceof Element)) continue;
                    Element repoBlock = (Element) n;
                    String url = repoBlock.getElementsByTagName("url").item(0).getTextContent();
                    repositories.add(Repository.maven(url));
                }
            }
            if (node.getNodeName().equals("dependencies") || node.getNodeName().equals("dependencyManagement")) {
                NodeList deps = node.getChildNodes();
                for (int j = 0; j < deps.getLength(); j++) {
                    Node n = deps.item(j);
                    if (!(n instanceof Element)) continue;
                    Element dependencyBlock = (Element) n;
                    String groupId = dependencyBlock.getElementsByTagName("groupId").item(0).getTextContent();
                    String artifactId = dependencyBlock.getElementsByTagName("artifactId").item(0).getTextContent();
                    String version = "";
                    MavenScope scope = MavenScope.COMPILE;
                    try {
                        version = dependencyBlock.getElementsByTagName("version").item(0).getTextContent();
                        if (version.startsWith("${")) {
                            if (version.equals("${project.version}")) version = dependency.getVersion();
                            else {
                                // Search for property
                                version = getProperty(version.replace("${", "").replace("}", ""), list);
                            }
                        }
                    } catch (NullPointerException ignored) {
                        // If version is not declared, look for version in parent pom
                        version = getVersionFromParent(dependency, getParentPom(list));
                    }
                    try {
                        scope = MavenScope.fromString(dependencyBlock.getElementsByTagName("scope").item(0).getTextContent());
                    } catch (NullPointerException ignored) {
                    }
                    if (groupId.equals("${project.groupId}")) groupId = dependency.getGroupId();
                    
                    if (scope != null && scopes.contains(scope) && !version.isEmpty()) {
                        Dependency e = new Dependency(groupId, artifactId, version);
                        dependencies.add(e);
                    }
                }
                break;
            }
        }
        if (recursively) {
            repositories.addAll(searchRepositories);
            for (Dependency e : dependencies.toArray(new Dependency[0])) {
                dependencies.addAll(get(repositories, e));
            }
        }
        return dependencies;
    }

    /**
     * Returns a builder to construct a {@link TransitiveResolver}.
     *
     * @return A new {@link TransitiveResolver.Builder}.
     */
    @Contract("-> new")
    public static @NotNull TransitiveResolver.Builder builder() {
        return new Builder();
    }

    public static class Builder {

        /**
         * Constructs a new {@link Builder} instance.
         */
        Builder() {
        }

        private boolean recursively = true;
        private final Set<MavenScope> scopes = new LinkedHashSet<MavenScope>() {{
            add(MavenScope.COMPILE);
        }};
        private final Set<Repository> searchRepositories = new LinkedHashSet<Repository>() {{
            add(mavenCentral());
        }};

        /**
         * Sets whether the resolver should resolve dependencies recursively (i.e.
         * transitive dependencies of transitive dependencies)
         *
         * @param recursively True to resolve dependencies recursively, false otherwise.
         * @return The current builder instance.
         */
        public @NotNull Builder recursively(boolean recursively) {
            this.recursively = recursively;
            return this;
        }

        /**
         * Adds repositories to search for dependencies.
         *
         * @param repositories The repositories to search.
         * @return The current builder instance.
         */
        public @NotNull Builder repositories(@NotNull Repository... repositories) {
            Collections.addAll(searchRepositories, repositories);
            return this;
        }

        /**
         * Adds repositories to search for dependencies.
         *
         * @param repositories The repositories to search.
         * @return The current builder instance.
         */
        public @NotNull Builder repositories(@NotNull List<Repository> repositories) {
            searchRepositories.addAll(repositories);
            return this;
        }

        /**
         * Adds Maven scopes to consider when resolving dependencies.
         *
         * @param scopes The scopes to consider.
         * @return The current builder instance.
         */
        public @NotNull Builder scopes(MavenScope... scopes) {
            Collections.addAll(this.scopes, scopes);
            return this;
        }

        /**
         * Adds Maven scopes to consider when resolving dependencies.
         *
         * @param scopes The scopes to consider.
         * @return The current builder instance.
         */
        public @NotNull Builder scopes(List<MavenScope> scopes) {
            this.scopes.addAll(scopes);
            return this;
        }

        /**
         * Builds and returns a new {@link TransitiveResolver} instance with the current configuration.
         *
         * @return A new {@link TransitiveResolver} instance.
         */
        @Contract(pure = true, value = "-> new")
        public @NotNull TransitiveResolver build() {
            return new TransitiveResolver(
                    recursively,
                    Collections.unmodifiableList(new ArrayList<>(scopes)),
                    Collections.unmodifiableList(new ArrayList<>(searchRepositories))
            );
        }
    }
}
