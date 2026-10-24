package cl.coders.faketraveler;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MockIntervalTest {

    @Test
    public void zeroSecondsIsContinuousNotOneSecond() {
        assertEquals(MockInterval.CONTINUOUS_DELAY_MS, MockInterval.toDelayMillis(0));
        assertEquals(MockInterval.CONTINUOUS_DELAY_MS, MockInterval.toDelayMillis(-5));
        assertEquals(1000L, MockInterval.toDelayMillis(1));
        assertEquals(10_000L, MockInterval.toDelayMillis(10));
    }

    @Test
    public void positiveMillisecondDelaysAreNotRaised() {
        assertEquals(MockInterval.CONTINUOUS_DELAY_MS, MockInterval.sanitizeDelayMillis(0));
        assertEquals(MockInterval.CONTINUOUS_DELAY_MS, MockInterval.sanitizeDelayMillis(-1));
        assertEquals(50L, MockInterval.sanitizeDelayMillis(50));
        assertEquals(1000L, MockInterval.sanitizeDelayMillis(1000));
    }

    @Test
    public void continuousDelayRoundTripsToStoredZero() {
        assertEquals(0, MockInterval.toStoredSeconds(MockInterval.CONTINUOUS_DELAY_MS));
        assertEquals(0, MockInterval.toStoredSeconds(0));
        assertEquals(1, MockInterval.toStoredSeconds(1000));
        assertEquals(10, MockInterval.toStoredSeconds(10_000));
    }

    @Test
    public void speedDivisorMatchesTheDelayThatWillBeUsed() {
        assertEquals(MockInterval.CONTINUOUS_DELAY_MS, MockInterval.speedDivisorMillis(0), 0f);
        assertEquals(1000f, MockInterval.speedDivisorMillis(1), 0f);
    }

    @Test
    public void watchdogWaitsLongerThanAHealthyTick() {
        long continuous = MockInterval.toDelayMillis(0);
        assertEquals(5_000L, MockInterval.watchdogPeriodMillis(continuous));
        assertEquals(5_000L, MockInterval.stallLimitMillis(continuous));
        assertEquals(12_000L, MockInterval.watchdogPeriodMillis(10_000L));
        assertEquals(22_000L, MockInterval.stallLimitMillis(10_000L));
    }
}
