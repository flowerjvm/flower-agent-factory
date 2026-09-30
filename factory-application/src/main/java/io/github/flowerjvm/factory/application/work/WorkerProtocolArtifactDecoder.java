package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;

/** Strict parser boundary; implementations must reject unknown/duplicate JSON properties. */
public interface WorkerProtocolArtifactDecoder {
    CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content);

    CodingWorkerInputManifest decodeInputManifest(byte[] content);

    CandidateSourceManifest decodeCandidateSourceManifest(byte[] content);
}
