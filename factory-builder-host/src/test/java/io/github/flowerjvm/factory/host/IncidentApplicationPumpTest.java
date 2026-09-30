package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IncidentApplicationPumpTest {
    @Test void schedulesOnlyOneBoundedControlLaneAndCancelsWithoutInventingEffectCompletion() {
        var runner=mock(IncidentApplicationRunner.class);var recovery=mock(IncidentApplicationRecovery.class);
        var executor=mock(ScheduledExecutorService.class);ScheduledFuture<?> scheduled=mock(ScheduledFuture.class);
        doReturn(scheduled).when(executor).scheduleWithFixedDelay(any(Runnable.class),eq(0L),eq(250L),eq(TimeUnit.MILLISECONDS));
        var pump=new IncidentApplicationPump(runner,recovery,executor,Duration.ofMillis(250));
        assertFalse(pump.isRunning());pump.start();pump.start();assertTrue(pump.isRunning());
        var callback=ArgumentCaptor.forClass(Runnable.class);
        verify(executor,times(1)).scheduleWithFixedDelay(callback.capture(),eq(0L),eq(250L),eq(TimeUnit.MILLISECONDS));
        callback.getValue().run();verify(recovery).recoverBatch(32);verify(runner).drain(2);
        pump.stop();assertFalse(pump.isRunning());verify(scheduled).cancel(false);
        verifyNoMoreInteractions(runner,recovery);
    }
    @Test void aTransientScanFailureDoesNotCancelFutureScheduledRecovery() {
        var runner=mock(IncidentApplicationRunner.class);var recovery=mock(IncidentApplicationRecovery.class);
        var executor=mock(ScheduledExecutorService.class);ScheduledFuture<?> scheduled=mock(ScheduledFuture.class);
        doReturn(scheduled).when(executor).scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),any());
        when(recovery.recoverBatch(32)).thenThrow(new IllegalStateException("synthetic private detail")).thenReturn(0);
        var pump=new IncidentApplicationPump(runner,recovery,executor,Duration.ofMillis(250));pump.start();
        var callback=ArgumentCaptor.forClass(Runnable.class);verify(executor).scheduleWithFixedDelay(callback.capture(),anyLong(),anyLong(),any());
        assertDoesNotThrow(callback.getValue()::run);assertTrue(pump.isRunning());
        callback.getValue().run();verify(runner).drain(2);pump.stop();
    }
    @Test void subMillisecondAndNonPositiveIntervalsAreRejected() {
        for(var interval:java.util.List.of(Duration.ZERO,Duration.ofMillis(-1),Duration.ofNanos(1)))
            assertThrows(IllegalArgumentException.class,()->new IncidentApplicationPump(mock(IncidentApplicationRunner.class),
                    mock(IncidentApplicationRecovery.class),mock(ScheduledExecutorService.class),interval));
    }
}
