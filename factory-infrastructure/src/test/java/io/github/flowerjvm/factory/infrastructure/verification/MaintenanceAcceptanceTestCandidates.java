package io.github.flowerjvm.factory.infrastructure.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Native-test-only product candidates. Never execute these classes on the Factory host. */
final class MaintenanceAcceptanceTestCandidates {
    enum Mutation { NONE, WRONG_EVIDENCE_REFERENCE, WRONG_CONFLICT_CODE, LOST_GROUP_REFERENCE }

    static void write(Path root, Mutation mutation) throws IOException {
        Files.createDirectories(root);
        String source;
        try (var stream = MaintenanceAcceptanceTestCandidates.class.getResourceAsStream(
                "/factory-verification/maintenance/InvestigationAcceptanceApi.java")) {
            if (stream == null) throw new IOException("native candidate fixture is missing");
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String inserted = switch (mutation) {
            case NONE, WRONG_CONFLICT_CODE -> "";
            case WRONG_EVIDENCE_REFERENCE -> "if (!findings.isEmpty()) findings.get(0).put(\"evidenceRefs\", List.of(\"invented-reference\"));";
            case LOST_GROUP_REFERENCE -> "for (var finding : findings) { var refs = (List<?>) finding.get(\"evidenceRefs\"); if (refs.size() > 1) finding.put(\"evidenceRefs\", List.of(refs.get(0))); }";
        };
        source = source.replace("/*FACTORY_TEST_MUTATION*/", inserted);
        if (mutation == Mutation.WRONG_CONFLICT_CODE) {
            source = source.replace("return Map.of(\"errorCode\", \"EVIDENCE_ID_CONFLICT\");",
                    "return Map.of(\"errorCode\", \"INVALID_INCIDENT\");");
        }
        Path main = root.resolve("src/main/java/io/github/flowerjvm/pack/maintenance/InvestigationAcceptanceApi.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, source, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.github.flowerjvm.factory.fixture</groupId>
                  <artifactId>maintenance-acceptance-fixture</artifactId><version>1.0.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <dependencies>
                    <dependency><groupId>io.github.flowerjvm</groupId><artifactId>flower-core</artifactId><version>0.1.3</version></dependency>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.12.2</version><scope>test</scope></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-clean-plugin</artifactId><version>3.4.1</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                  </plugins></build>
                </project>
                """, StandardCharsets.UTF_8);
        Path test = root.resolve("src/test/java/fixture/CandidateSmokeTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
                package fixture;
                final class CandidateSmokeTest {
                    @org.junit.jupiter.api.Test void candidateOwnTestsPassEvenWithSemanticMutations() {
                        org.junit.jupiter.api.Assertions.assertEquals("INVALID_INCIDENT",
                            io.github.flowerjvm.pack.maintenance.InvestigationAcceptanceApi.investigate(java.util.Map.of()).get("errorCode"));
                    }
                }
                """, StandardCharsets.UTF_8);
    }
}
