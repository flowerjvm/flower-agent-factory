package io.github.flowerjvm.factory.infrastructure.verification;

import java.util.List;

/** Host-selected commands; candidate manifests cannot add or replace command arguments. */
public enum VerificationCommand {
    MAVEN_VERIFY(
            "maven-fresh-output-verify",
            List.of(
                    "mvn", "-B", "-ntp", "-o",
                    "-Dmaven.repo.local=/m2",
                    "-Dmaven.clean.skip=false",
                    "-Dmaven.test.skip=false",
                    "-DskipTests=false",
                    "-Dsurefire.skipTests=false",
                    "-DfailIfNoTests=true",
                    "-Dsurefire.failIfNoSpecifiedTests=true",
                    "-Dsurefire.useFile=false",
                    "-Dmaven.compiler.proc=none",
                    "-Dflower.check.skip=false",
                    "verify")),
    FLOWER_CHECK(
            "flower-check-0.1.3-strict",
            List.of(
                    "mvn",
                    "-B",
                    "-ntp",
                    "-o",
                    "-Dmaven.repo.local=/m2",
                    "io.github.flowerjvm:flower-check-maven-plugin:0.1.3:check",
                    "-Dflower.check.skip=false",
                    "-Dflower.check.strictParsing=true",
                    "-Dflower.check.format=sarif",
                    "-Dflower.check.outputFile=/workspace/.factory-evidence/flower-check.sarif")),
    MAVEN_DEPENDENCY_TREE(
            "maven-dependency-tree",
            List.of(
                    "mvn", "-B", "-ntp", "-o",
                    "-Dmaven.repo.local=/m2",
                    "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree",
                    "-DoutputType=text",
                    "-DoutputFile=/workspace/.factory-evidence/dependency-tree.txt")),
    MAINTENANCE_ACCEPTANCE(
            "maintenance-factory-owned-acceptance-v1",
            List.of("java", "/workspace/factory-probe/FactoryAcceptanceProbe.java"));

    private final String commandId;
    private final List<String> arguments;

    VerificationCommand(String commandId, List<String> arguments) {
        this.commandId = commandId;
        this.arguments = List.copyOf(arguments);
    }

    public String commandId() {
        return commandId;
    }

    public List<String> arguments() {
        return arguments;
    }
}
