package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.BUILD_POLICY_VIOLATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.verification.MavenRepositoryFile;
import io.github.flowerjvm.factory.contracts.verification.MavenToolchainLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateMavenBuildPolicyTest {
    @TempDir Path temporaryDirectory;
    private final CandidateMavenBuildPolicy policy = new CandidateMavenBuildPolicy();

    @Test
    void allowsOnlyExactTrustedPluginIdentityAndSafeCompilerProperty() throws Exception {
        Path workspace = workspace("""
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                %s
                """.formatted(lifecyclePlugins()));
        assertDoesNotThrow(() -> policy.validate(workspace, toolchain()));
    }

    @Test
    void rejectsParentAndChildModuleBypass() throws Exception {
        assertViolation("<parent><groupId>x</groupId><artifactId>p</artifactId><version>1</version></parent>");
        assertViolation("<modules><module>child</module></modules>");
    }

    @Test
    void rejectsLifecycleExecutionPluginDependencyAndNoTestsSpoof() throws Exception {
        assertViolation("""
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>
                <executions><execution><phase>test</phase><goals><goal>test</goal></goals></execution></executions>
                </plugin></plugins></build>
                """);
        assertViolation("""
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>
                <dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1</version></dependency></dependencies>
                </plugin></plugins></build>
                """);
        assertViolation("<properties><skipTests>true</skipTests></properties>");
        assertViolation("""
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version><version>9.9.9</version>
                </plugin></plugins></build>
                """);
        assertViolation("""
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.5.2</version></plugin></plugins></build>
                """);
        assertViolation(lifecyclePlugins() + lifecyclePlugins());
        assertViolation("""
                <build><pluginManagement><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version></plugin></plugins></pluginManagement></build>
                """);
    }

    private void assertViolation(String body) throws Exception {
        var failure = assertThrows(CandidateMaterializationException.class,
                () -> policy.validate(workspace(body), toolchain()));
        assertEquals(BUILD_POLICY_VIOLATION, failure.stableCode());
    }

    private Path workspace(String body) throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("case-" + Files.list(temporaryDirectory).count()));
        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>y</artifactId><version>1</version>
                %s
                </project>
                """.formatted(body));
        return workspace;
    }

    private static MavenToolchainLock toolchain() {
        var file = new MavenRepositoryFile(
                "org/apache/maven/plugins/maven-surefire-plugin/3.5.2/maven-surefire-plugin-3.5.2.jar",
                new ArtifactReference("tool"), new ContentHash("1".repeat(64)), 1);
        return new MavenToolchainLock(
                MavenToolchainLock.SCHEMA_VERSION, MavenToolchainLock.REPOSITORY_ID,
                MavenToolchainLock.REPOSITORY_URL,
                Pr4MavenToolchainInstaller.ALLOWED_BUILD_PLUGINS, 1, 1, List.of(file));
    }

    private static String lifecyclePlugins() {
        return """
                <build><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-clean-plugin</artifactId><version>3.4.1</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                </plugins></build>
                """;
    }
}
