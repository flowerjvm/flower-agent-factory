package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VerificationEvidenceParserTest {
    @Test
    void countsOnlyBoundedSuccessfulSurefireSuite() {
        byte[] report = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="ExampleTest" tests="3" failures="0" errors="0" skipped="1">
                  <testcase name="one"/><testcase name="two"/><testcase name="three"/>
                </testsuite>
                """.getBytes(StandardCharsets.UTF_8);

        assertEquals(2, IndependentMavenVerifier.verifiedTestCount(Map.of("surefire/TEST-Example.xml", report)));
    }

    @Test
    void rejectsDeepWideTextHeavyAndNestedSurefireReports() {
        String deep = "<testsuite tests=\"1\" failures=\"0\" errors=\"0\">"
                + "<a>".repeat(32) + "x" + "</a>".repeat(32) + "</testsuite>";
        String wide = "<testsuite tests=\"1\" failures=\"0\" errors=\"0\">"
                + "<a/>".repeat(5_001) + "</testsuite>";
        String textHeavy = "<testsuite tests=\"1\" failures=\"0\" errors=\"0\"><system-out>"
                + "x".repeat(64 * 1024 + 1) + "</system-out></testsuite>";
        String nested = "<testsuite tests=\"1\" failures=\"0\" errors=\"0\">"
                + "<testsuite tests=\"1\" failures=\"0\" errors=\"0\"/></testsuite>";

        for (String report : java.util.List.of(deep, wide, textHeavy, nested)) {
            assertEquals(0, IndependentMavenVerifier.verifiedTestCount(Map.of(
                    "surefire/TEST-Adversarial.xml", report.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test
    void acceptsBoundedRealSurefirePropertyAttributesWithoutWeakeningRootCounts() {
        String report = """
                <testsuite tests="1" failures="0" errors="0" skipped="0">
                  <properties><property name="java.class.path" value="%s"/></properties>
                  <testcase name="works" classname="FixtureTest" time="0.001"/>
                </testsuite>
                """.formatted("x".repeat(4_096));

        assertEquals(1, IndependentMavenVerifier.verifiedTestCount(
                java.util.Map.of("surefire/TEST-FixtureTest.xml", report.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
