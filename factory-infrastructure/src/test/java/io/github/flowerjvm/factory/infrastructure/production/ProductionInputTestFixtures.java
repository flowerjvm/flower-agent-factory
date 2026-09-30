package io.github.flowerjvm.factory.infrastructure.production;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Synthetic installed-source inputs, not candidate code or evidence of a production run. */
final class ProductionInputTestFixtures {
    static final ArtifactStore NO_STORE_ACCESS = new ArtifactStore() {
        @Override public ArtifactReference store(Artifact artifact) { throw new AssertionError("preflight must not store"); }
        @Override public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            throw new AssertionError("preflight must not read ArtifactStore");
        }
    };

    static Path plugin(Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("plugin/skills"));
        Path descriptor = root.getParent().resolve(".codex-plugin/plugin.json");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, "{\"name\":\"flower\",\"version\":\"0.3.3\",\"skills\":\"./skills/\"}", StandardCharsets.UTF_8);
        for (String relative : AgentPackProductionInputAssembler.SKILL_PATHS) {
            String content = "Test-only original reference text: " + relative + "\r\n한글 preserved\n";
            if (relative.endsWith("/SKILL.md")) content = "---\nname: " + relative.split("/")[0] + "\n---\n" + content;
            if (relative.equals("flower-app-guide/references/00-guide-version.md")) {
                content += "Guide version: `0.7.0`\nTarget Flower version: `0.1.3`\n";
            }
            if (relative.equals("flower-action-runtime-guide/references/00-guide-version.md")) {
                content += "Guide version: `0.6.0`\nTarget runtime line: `flower-action-runtime 0.3.3`\n";
            }
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
        return root;
    }

    private ProductionInputTestFixtures() {}
}
