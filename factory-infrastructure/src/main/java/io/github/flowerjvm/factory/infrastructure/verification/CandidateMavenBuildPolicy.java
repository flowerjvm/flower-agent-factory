package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.BUILD_POLICY_VIOLATION;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import io.github.flowerjvm.factory.contracts.verification.MavenToolchainLock;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Rejects candidate-controlled Maven hooks that could forge host verification evidence. */
final class CandidateMavenBuildPolicy {
    private static final int MAX_POM_BYTES = 512 * 1024;
    private static final Set<String> ALLOWED_PROPERTY_NAMES = Set.of(
            "maven.compiler.release",
            "maven.compiler.source",
            "maven.compiler.target",
            "project.build.sourceEncoding",
            "project.reporting.outputEncoding");
    private static final Set<String> REQUIRED_LIFECYCLE_PLUGINS = Set.of(
            "org.apache.maven.plugins:maven-clean-plugin",
            "org.apache.maven.plugins:maven-resources-plugin",
            "org.apache.maven.plugins:maven-compiler-plugin",
            "org.apache.maven.plugins:maven-surefire-plugin",
            "org.apache.maven.plugins:maven-jar-plugin");

    void validate(Path workspace, MavenToolchainLock toolchain) throws CandidateMaterializationException {
        Map<String, String> allowedPlugins = toolchain.allowedBuildPlugins().stream()
                .collect(Collectors.toUnmodifiableMap(
                        value -> value.substring(0, value.lastIndexOf(':')),
                        value -> value.substring(value.lastIndexOf(':') + 1)));
        rejectCandidateMavenControlFiles(workspace);
        Path pom = workspace.resolve("pom.xml");
        try {
            if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(pom)
                    || Files.size(pom) > MAX_POM_BYTES) {
                throw violation("root pom.xml is missing or exceeds the fixed parse bound");
            }
            String xml = Files.readString(pom, StandardCharsets.UTF_8);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            var document = builder.parse(new InputSource(new StringReader(xml)));
            Element project = document.getDocumentElement();
            if (!"project".equals(project.getLocalName())
                    || !"http://maven.apache.org/POM/4.0.0".equals(project.getNamespaceURI())
                    || directChildCount(project, "build") > 1
                    || directChildCount(project, "properties") > 1) {
                throw violation("candidate pom.xml must use the canonical single-project shape");
            }
            if (document.getElementsByTagNameNS("*", "pluginRepositories").getLength() > 0
                    || document.getElementsByTagNameNS("*", "repositories").getLength() > 0
                    || document.getElementsByTagNameNS("*", "extensions").getLength() > 0
                    || document.getElementsByTagNameNS("*", "modules").getLength() > 0
                    || document.getElementsByTagNameNS("*", "parent").getLength() > 0
                    || document.getElementsByTagNameNS("*", "executions").getLength() > 0
                    || document.getElementsByTagNameNS("*", "reporting").getLength() > 0
                    || document.getElementsByTagNameNS("*", "profiles").getLength() > 0
                    || document.getElementsByTagNameNS("*", "systemPath").getLength() > 0
                    || document.getElementsByTagNameNS("*", "testSourceDirectory").getLength() > 0
                    || document.getElementsByTagNameNS("*", "directory").getLength() > 0) {
                throw violation("candidate Maven repositories and build extensions are forbidden");
            }
            var properties = document.getElementsByTagNameNS("*", "properties");
            for (int index = 0; index < properties.getLength(); index++) {
                Node property = properties.item(index).getFirstChild();
                while (property != null) {
                    String name = property.getLocalName() == null ? property.getNodeName() : property.getLocalName();
                    if (property instanceof Element
                            && !ALLOWED_PROPERTY_NAMES.contains(name)) {
                        throw violation("candidate property is not in the narrow build allowlist");
                    }
                    if (property instanceof Element element) {
                        validatePropertyValue(name, element.getTextContent().trim());
                    }
                    property = property.getNextSibling();
                }
            }
            var plugins = document.getElementsByTagNameNS("*", "plugin");
            var declaredPlugins = new java.util.HashSet<String>();
            for (int index = 0; index < plugins.getLength(); index++) {
                Element plugin = (Element) plugins.item(index);
                String groupId = childText(plugin, "groupId", "org.apache.maven.plugins");
                String artifactId = childText(plugin, "artifactId", "");
                String version = childText(plugin, "version", "");
                String coordinate = groupId + ":" + artifactId;
                if (!declaredPlugins.add(coordinate)
                        || !version.equals(allowedPlugins.get(coordinate))
                        || childElement(plugin, "configuration") != null
                        || childElement(plugin, "dependencies") != null
                        || !isDirectBuildPlugin(plugin, project)
                        || !hasOnlyPluginIdentityChildren(plugin)) {
                    throw violation("candidate Maven plugins must exactly match the trusted toolchain lock");
                }
            }
            if (!declaredPlugins.containsAll(REQUIRED_LIFECYCLE_PLUGINS)) {
                throw violation("candidate must declare every trusted lifecycle plugin at its exact host version");
            }
            validateBuildShape(project);
        } catch (CandidateMaterializationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CandidateMaterializationException(
                    BUILD_POLICY_VIOLATION, "candidate pom.xml could not be parsed safely", exception);
        }
    }

    private static boolean hasOnlyPluginIdentityChildren(Element plugin) {
        var children = plugin.getChildNodes();
        var counts = new java.util.HashMap<String, Integer>();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (!(child instanceof Element)) continue;
            String name = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if (!Set.of("groupId", "artifactId", "version").contains(name)) return false;
            counts.merge(name, 1, Integer::sum);
        }
        return counts.getOrDefault("groupId", 0) <= 1
                && counts.getOrDefault("artifactId", 0) == 1
                && counts.getOrDefault("version", 0) == 1;
    }

    private static void validatePropertyValue(String name, String value)
            throws CandidateMaterializationException {
        if (name.startsWith("maven.compiler.") && !value.matches("[0-9]{1,2}")) {
            throw violation("compiler level must be a bounded decimal release");
        }
        if (name.endsWith("Encoding") && !value.matches("[A-Za-z0-9._-]{1,32}")) {
            throw violation("encoding must be a bounded canonical name");
        }
    }

    private static void validateBuildShape(Element project) throws CandidateMaterializationException {
        Element build = childElement(project, "build");
        if (build == null) return;
        var children = build.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (!(child instanceof Element)) continue;
            String name = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if (!"plugins".equals(name)) {
                throw violation("candidate build shape is outside the narrow Maven allowlist");
            }
        }
        if (directChildCount(build, "plugins") != 1) {
            throw violation("candidate build must have one direct plugins element");
        }
    }

    private static boolean isDirectBuildPlugin(Element plugin, Element project) {
        Node plugins = plugin.getParentNode();
        Node build = plugins == null ? null : plugins.getParentNode();
        return plugins instanceof Element pluginsElement
                && build instanceof Element buildElement
                && "plugins".equals(pluginsElement.getLocalName())
                && "build".equals(buildElement.getLocalName())
                && buildElement.getParentNode() == project
                && plugin.getNamespaceURI().equals(project.getNamespaceURI())
                && pluginsElement.getNamespaceURI().equals(project.getNamespaceURI())
                && buildElement.getNamespaceURI().equals(project.getNamespaceURI());
    }

    private static int directChildCount(Element parent, String name) {
        int count = 0;
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                count++;
            }
        }
        return count;
    }

    private static void rejectCandidateMavenControlFiles(Path workspace)
            throws CandidateMaterializationException {
        for (String path : Set.of(
                ".mvn/extensions.xml", ".mvn/maven.config", ".mvn/jvm.config",
                "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.jar",
                ".mvn/wrapper/maven-wrapper.properties")) {
            if (Files.exists(workspace.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                throw violation("candidate Maven bootstrap/control files are forbidden");
            }
        }
    }

    private static String childText(Element parent, String name, String fallback) {
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if (name.equals(local)) {
                return child.getTextContent().trim();
            }
        }
        return fallback;
    }

    private static Element childElement(Element parent, String name) {
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if (child instanceof Element element && name.equals(local)) {
                return element;
            }
        }
        return null;
    }

    private static CandidateMaterializationException violation(String message) {
        return new CandidateMaterializationException(BUILD_POLICY_VIOLATION, message);
    }
}
