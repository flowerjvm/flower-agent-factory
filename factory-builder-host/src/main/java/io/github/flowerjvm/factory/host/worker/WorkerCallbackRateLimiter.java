package io.github.flowerjvm.factory.host.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Two-stage single-host admission limiter for the internal callback endpoint.
 *
 * <p>Before authentication, a bounded transport-source bucket and a global bucket protect body
 * reads and HMAC work. Only a successfully authenticated request consumes its credential-derived
 * tenant/binding bucket, so merely knowing a key id cannot starve that worker's callback budget.
 * Only the first blocked request per bucket/window is selected for durable audit, preventing the
 * audit table from becoming a denial-of-service amplifier.
 */
public final class WorkerCallbackRateLimiter {
    private static final int MAX_SOURCE_BUCKETS = 1_024;
    private static final int MAX_AUTHENTICATED_BUCKETS = 256;
    public static final int DEFAULT_AUTHENTICATED_LIMIT = 32;
    public static final int DEFAULT_SOURCE_LIMIT = 8;
    public static final int DEFAULT_GLOBAL_LIMIT = 128;
    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(1);

    private final Clock clock;
    private final Duration window;
    private final int sourceLimit;
    private final int authenticatedLimit;
    private final Bucket global;
    private final Map<String, Bucket> sources = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Bucket> authenticated = new LinkedHashMap<>(16, 0.75f, true);

    public WorkerCallbackRateLimiter(Clock clock) {
        this(
                clock,
                DEFAULT_AUTHENTICATED_LIMIT,
                DEFAULT_SOURCE_LIMIT,
                DEFAULT_GLOBAL_LIMIT,
                DEFAULT_WINDOW);
    }

    WorkerCallbackRateLimiter(
            Clock clock,
            int authenticatedLimit,
            int sourceLimit,
            int globalLimit,
            Duration window) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.window = Objects.requireNonNull(window, "window");
        if (authenticatedLimit < 1 || sourceLimit < 1 || globalLimit < 1
                || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("callback rate limits and window must be positive");
        }
        this.authenticatedLimit = authenticatedLimit;
        this.sourceLimit = sourceLimit;
        this.global = new Bucket(globalLimit, clock.instant());
    }

    public synchronized Decision acquirePreAuthentication(String transportSource) {
        Instant now = clock.instant();
        // Consume the non-evictable global budget first. Otherwise every newly evicted source
        // bucket could select another durable audit sample and turn the audit ledger itself into
        // an amplification surface under a high-cardinality source flood.
        Decision globalDecision = acquire(global, now);
        if (!globalDecision.allowed()) {
            return globalDecision;
        }
        Bucket source = bucket(
                sources,
                boundedKey(transportSource, "UNRESOLVED"),
                sourceLimit,
                MAX_SOURCE_BUCKETS,
                now);
        return acquire(source, now);
    }

    public synchronized Decision acquireAuthenticated(String credentialBucketKey) {
        Instant now = clock.instant();
        Bucket bucket = bucket(
                authenticated,
                boundedKey(credentialBucketKey, null),
                authenticatedLimit,
                MAX_AUTHENTICATED_BUCKETS,
                now);
        return acquire(bucket, now);
    }

    private Decision acquire(Bucket bucket, Instant now) {
        if (now.isBefore(bucket.windowStart) || !now.isBefore(bucket.windowStart.plus(window))) {
            bucket.windowStart = now;
            bucket.accepted = 0;
            bucket.blocked = 0;
        }
        if (bucket.accepted < bucket.limit) {
            bucket.accepted++;
            return new Decision(true, false, now);
        }
        bucket.blocked++;
        return new Decision(false, bucket.blocked == 1, now);
    }

    private static Bucket bucket(
            Map<String, Bucket> buckets,
            String key,
            int limit,
            int maximumBuckets,
            Instant now) {
        Bucket current = buckets.get(key);
        if (current != null) {
            return current;
        }
        if (buckets.size() >= maximumBuckets) {
            buckets.remove(buckets.keySet().iterator().next());
        }
        Bucket created = new Bucket(limit, now);
        buckets.put(key, created);
        return created;
    }

    private static String boundedKey(String value, String fallback) {
        if (value == null || value.isBlank() || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("authenticated callback bucket key is invalid");
        }
        return value;
    }

    public record Decision(boolean allowed, boolean auditSelected, Instant observedAt) {
        public Decision {
            Objects.requireNonNull(observedAt, "observedAt");
            if (allowed && auditSelected) {
                throw new IllegalArgumentException("an allowed request is not a blocked audit sample");
            }
        }
    }

    private static final class Bucket {
        private final int limit;
        private Instant windowStart;
        private int accepted;
        private int blocked;

        private Bucket(int limit, Instant windowStart) {
            this.limit = limit;
            this.windowStart = windowStart;
        }
    }
}
