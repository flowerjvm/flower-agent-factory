package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.flow.CreateCustomerAgentFlowFactory;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationService;
import io.github.flowerjvm.factory.application.flow.FlowCancellationDisposition;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineRegistry;
import io.github.flowerjvm.factory.application.work.WorkerCancellationRequester;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.flow.FlowSnapshot;
import io.github.flowerjvm.flower.core.listener.FlowerListener;
import io.github.flowerjvm.flower.core.persistence.CheckpointStoreCapabilities;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpoint;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpointStore;
import io.github.flowerjvm.flower.core.time.ManualClock;
import io.github.flowerjvm.flower.core.worker.DuplicatePolicy;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FactoryFlowerConfigurationTest {
    private static final BuildSessionId SESSION_ID = new BuildSessionId("pr3-host-session");
    private static final FlowId FLOW_ID = FlowId.of(CreateCustomerAgentFlowFactory.FLOW_TYPE, SESSION_ID.value());
    private static final Instant FIXTURE_TIME = Instant.parse("2026-09-06T00:00:00Z");

    @Test
    void jdbcCheckpointAndRegistryRecoverBeforeManualTicksWithFullIdentity() {
        DataSource dataSource = dataSource();
        ExecutionContext expected = CreateCustomerAgentFlowFactory.executionContext(
                SESSION_ID, "tenant-a", "principal-a", "flow-run-a", "trace-a", "project-a");

        try (var first = context(dataSource)) {
            BuildSession session = session();
            first.getBean(BuildSessionRepository.class).create(session);
            Engine engine = first.getBean(Engine.class);
            engine.submit(
                    FactoryFlowerConfiguration.FACTORY_WORKER,
                    first.getBean(FactoryProductLineRegistry.class)
                            .create(session, "flow-run-a", "trace-a"),
                    DuplicatePolicy.REJECT);
            FlowSnapshot active = tickOnceDurablyAt(first, CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE);

            assertEquals(
                    CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE,
                    active.currentStepId());
            FlowCheckpointStore store = first.getBean(FlowCheckpointStore.class);
            var checkpoint = store.find(FLOW_ID).orElseThrow();
            assertEquals(expected, checkpoint.executionContext());
            assertEquals(FactoryFlowerConfiguration.FACTORY_WORKER, checkpoint.workerName());
            assertEquals(FIXTURE_TIME.toEpochMilli(), checkpoint.updatedAtMillis());
            assertEquals(FIXTURE_TIME, first.getBean(Clock.class).instant());
            assertEquals(FIXTURE_TIME, first.getBean(BuildSessionRepository.class)
                    .find(session.tenantId(), SESSION_ID).orElseThrow().updatedAt());
            engine.stop();
            assertEquals(1, store.findActiveByWorker(FactoryFlowerConfiguration.FACTORY_WORKER).size());
        }

        try (var restarted = context(dataSource)) {
            Engine engine = restarted.getBean(Engine.class);
            assertEquals(1, restarted.getBean(FlowCheckpointStore.class)
                    .findActiveByWorker(FactoryFlowerConfiguration.FACTORY_WORKER).size());
            FlowSnapshot recovered = tickOnceDurablyAt(
                    restarted, CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE);

            assertEquals(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE, recovered.currentStepId());
            assertEquals(expected, recovered.executionContext());
            assertTrue(restarted.getBean(FlowCheckpointStore.class).find(FLOW_ID).isPresent());
            engine.stop();
        }
    }

    @Test
    void cancellationPersistsBeforeFlowerControlAndTerminatesBothLedgers() {
        DataSource dataSource = dataSource();
        try (var context = context(dataSource)) {
            BuildSession session = session();
            BuildSessionRepository sessions = context.getBean(BuildSessionRepository.class);
            sessions.create(session);
            Engine engine = context.getBean(Engine.class);
            engine.submit(
                    FactoryFlowerConfiguration.FACTORY_WORKER,
                    context.getBean(FactoryProductLineRegistry.class)
                            .create(session, "flow-run-cancel", "trace-cancel"),
                    DuplicatePolicy.REJECT);
            engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER).tickOnce();

            assertEquals(
                    FlowCancellationDisposition.CANCELLATION_REQUESTED,
                    new FactoryFlowCancellationService(
                                    sessions,
                                    engine,
                                    context.getBean(FactoryProductLineRegistry.class),
                                    new WorkerCancellationRequester(
                                            context.getBean(WorkerRunRepository.class),
                                            context.getBean(DefaultActionRuntime.class)),
                                    java.time.Clock.fixed(
                                            sessions.find(session.tenantId(), session.buildSessionId())
                                                    .orElseThrow()
                                                    .updatedAt()
                                                    .plusMillis(1),
                                            java.time.ZoneOffset.UTC))
                            .cancel(session.tenantId(), session.buildSessionId()));
            assertEquals(
                    BuildSessionStatus.CANCELLED,
                    sessions.find(session.tenantId(), session.buildSessionId()).orElseThrow().status());
            engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER).tickOnce();
            assertTrue(engine.checkpointStore().find(FLOW_ID).isEmpty());
            assertTrue(engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER).snapshot().isEmpty());
            engine.stop();
        }
    }

    @Test
    void terminalStepFailureRetainsOriginalCauseAfterFlowerRemovesTheFlow() {
        var cause = new IllegalStateException(
                "injected coordinator failure", new IllegalArgumentException("injected root cause"));
        try (var context = context(dataSource())) {
            BuildSession session = session();
            context.getBean(BuildSessionRepository.class).create(session);
            Engine engine = context.getBean(Engine.class);
            engine.submit(
                    FactoryFlowerConfiguration.FACTORY_WORKER,
                    new CreateCustomerAgentFlowFactory((phase, stepContext) -> {
                        throw cause;
                    }).create(session, "flow-run-failure", "trace-failure"),
                    DuplicatePolicy.REJECT);

            AssertionError failure = assertThrows(AssertionError.class,
                    () -> tickOnceDurablyAt(context, CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE));

            assertSame(cause, failure.getCause());
            assertTrue(failure.getMessage().contains("injected coordinator failure"));
            assertTrue(failure.getMessage().contains("injected root cause"));
            assertTrue(failure.getMessage().contains("terminal=FlowSnapshot{"));
            assertTrue(failure.getMessage().contains("FAILED"));
            assertTrue(failure.getMessage().contains("BuildSession=Optional[BuildSession["));
            assertTrue(engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER).snapshot().isEmpty());
            assertTrue(engine.checkpointStore().find(FLOW_ID).isEmpty());
            engine.stop();
        }
    }

    @Test
    void checkpointFailureRetainsWorkerAndTerminalEvidenceInsteadOfReportingMissingProgress() {
        var cause = new IllegalStateException("injected checkpoint save failure");
        try (var context = context(dataSource(), cause)) {
            BuildSession session = session();
            context.getBean(BuildSessionRepository.class).create(session);
            Engine engine = context.getBean(Engine.class);
            engine.submit(
                    FactoryFlowerConfiguration.FACTORY_WORKER,
                    context.getBean(FactoryProductLineRegistry.class)
                            .create(session, "flow-run-checkpoint-failure", "trace-checkpoint-failure"),
                    DuplicatePolicy.REJECT);

            AssertionError failure = assertThrows(AssertionError.class,
                    () -> tickOnceDurablyAt(context, CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE));

            assertSame(cause, failure.getCause());
            assertTrue(failure.getMessage().contains("injected checkpoint save failure"));
            assertTrue(failure.getMessage().contains("CHECKPOINT_FAILED"));
            assertTrue(failure.getMessage().contains("worker=" + FactoryFlowerConfiguration.FACTORY_WORKER));
            assertEquals(List.of(cause), context.getBean(FlowDiagnostics.class).workerFailures);
            assertTrue(engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER).snapshot().isEmpty());
            // Initial READY checkpoint survived; a failed later save must not erase that recovery evidence.
            assertTrue(engine.checkpointStore().find(FLOW_ID).isPresent());
            engine.stop();
        }
    }


    private static AnnotationConfigApplicationContext context(DataSource dataSource) {
        return context(dataSource, null);
    }

    private static AnnotationConfigApplicationContext context(
            DataSource dataSource, RuntimeException checkpointFailure) {
        var context = new AnnotationConfigApplicationContext();
        var diagnostics = new FlowDiagnostics();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(FlowDiagnostics.class, () -> diagnostics);
        context.registerBean("fixtureClock", Clock.class,
                () -> Clock.fixed(FIXTURE_TIME, ZoneOffset.UTC), definition -> definition.setPrimary(true));
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FlowCheckpointStore store && checkpointFailure != null) {
                    return new FailingCheckpointStore(store, checkpointFailure);
                }
                if (bean instanceof Engine productionEngine) {
                    // Preserve the actual host's workers, event bus and JDBC store. Only the test
                    // clock and observer change, before the host's real recovery hook attaches it.
                    var builder = Engine.builder()
                            .clock(new ManualClock(FIXTURE_TIME.toEpochMilli()))
                            .eventBus(productionEngine.eventBus())
                            .checkpointStore(productionEngine.checkpointStore())
                            .listener(diagnostics);
                    productionEngine.workers().forEach(builder::worker);
                    productionEngine.listeners().forEach(builder::listener);
                    return builder.build();
                }
                return bean;
            }
        });
        context.register(FactoryActionRuntimeConfiguration.class, FactoryFlowerConfiguration.class);
        context.refresh();
        return context;
    }

    private static FlowSnapshot tickOnceDurablyAt(
            AnnotationConfigApplicationContext context, String expectedStepId) {
        Engine engine = context.getBean(Engine.class);
        var worker = engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER);
        var diagnostics = context.getBean(FlowDiagnostics.class);
        // Both initial ACCEPT_REQUIREMENTS and recovery at DESIGN_CANDIDATE are synchronous
        // single-tick transitions. Repeating ticks or sleeping would obscure a real failure.
        try {
            worker.tickOnce();
        } catch (RuntimeException failure) {
            diagnostics.onWorkerError(worker.name(), failure);
        }
        Optional<FlowSnapshot> snapshot = worker.snapshot().stream()
                .filter(flow -> FLOW_ID.equals(flow.flowId()))
                .filter(flow -> expectedStepId.equals(flow.currentStepId()))
                .findFirst();
        var checkpoint = engine.checkpointStore().find(FLOW_ID);
        boolean checkpointMatches = checkpoint
                .filter(value -> expectedStepId.equals(value.currentStepId())).isPresent();
        if (snapshot.isPresent() && checkpointMatches && !diagnostics.failed()) {
            return snapshot.orElseThrow();
        }
        throw new AssertionError(
                "Flow did not reach durable step " + expectedStepId + " after one synchronous manual tick.\n"
                        + diagnostics.describe()
                        + "\ncheckpoint=" + checkpoint
                        + "\nBuildSession=" + context.getBean(BuildSessionRepository.class)
                                .find(new TenantId("tenant-a"), SESSION_ID)
                        + "\n" + engine.dump(),
                diagnostics.failureCause());
    }

    private static BuildSession session() {
        Instant now = FIXTURE_TIME;
        return new BuildSession(
                SESSION_ID,
                new TenantId("tenant-a"),
                new ProjectId("project-a"),
                ProductLineId.AGENT_PACK,
                "request-pr3-host",
                "principal-a",
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                new ArtifactReference("artifact:requirements"),
                new ContentHash("1".repeat(64)),
                Optional.empty(),
                Optional.of("fake-coding-worker"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                3,
                now,
                now.plusSeconds(3600),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
    }

    private static final class FlowDiagnostics implements FlowerListener {
        private FlowSnapshot terminal;
        private final List<Throwable> workerFailures = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public void onFlowFailed(FlowSnapshot flow, Throwable cause) {
            terminal = flow;
            events.add("flow=" + flow.flowId() + " cause=" + stackTrace(cause));
        }

        @Override
        public void onFlowFinished(FlowSnapshot flow) {
            terminal = flow;
        }

        @Override
        public void onFlowCancelled(FlowSnapshot flow) {
            terminal = flow;
        }

        @Override
        public void onWorkerError(String workerName, Throwable cause) {
            workerFailures.add(cause);
            events.add("worker=" + workerName + " cause=" + stackTrace(cause));
        }

        @Override
        public void onListenerError(FlowSnapshot flow, String callbackName, Throwable cause) {
            workerFailures.add(cause);
            events.add("listener=" + callbackName + " flow=" + flow.flowId() + " cause=" + stackTrace(cause));
        }

        private boolean failed() {
            return terminal != null || !workerFailures.isEmpty();
        }

        private Throwable failureCause() {
            if (terminal != null && terminal.failureCause() != null) {
                return terminal.failureCause();
            }
            return workerFailures.isEmpty() ? null : workerFailures.getFirst();
        }

        private String describe() {
            return "terminal=" + terminal + "\nevents=" + events;
        }

        private static String stackTrace(Throwable cause) {
            var text = new StringWriter();
            cause.printStackTrace(new PrintWriter(text));
            return text.toString();
        }
    }

    private record FailingCheckpointStore(
            FlowCheckpointStore delegate, RuntimeException failure) implements FlowCheckpointStore {
        @Override
        public CheckpointStoreCapabilities capabilities() {
            return delegate.capabilities();
        }

        @Override
        public void save(FlowCheckpoint checkpoint) {
            if (CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE.equals(checkpoint.currentStepId())) {
                throw failure;
            }
            delegate.save(checkpoint);
        }

        @Override
        public void delete(FlowId flowId) {
            delegate.delete(flowId);
        }

        @Override
        public Optional<FlowCheckpoint> find(FlowId flowId) {
            return delegate.find(flowId);
        }

        @Override
        public List<FlowCheckpoint> findActive() {
            return delegate.findActive();
        }

        @Override
        public List<FlowCheckpoint> findActiveByWorker(String workerName) {
            return delegate.findActiveByWorker(workerName);
        }
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
