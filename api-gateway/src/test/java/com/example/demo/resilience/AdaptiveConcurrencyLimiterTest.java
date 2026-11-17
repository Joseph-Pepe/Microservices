package com.example.demo.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveConcurrencyLimiterTest {

    @Test
    void testHealthyUtilizedTraffic_shouldGrowLimitAndIdleTrafficShouldNotInflate() {
        var limiter = new AdaptiveConcurrencyLimiter("rest", 16, 4, 64, 1.5, 0L, 2);

        // 1. Idle/app-limited traffic (inFlight = 2 < 16/2 = 8): limit stays 16
        for (int i = 0; i < 12; i++) {
            var res = limiter.tryAcquire();
            assertThat(res.admitted()).isTrue();
            limiter.releaseSuccess(10_000_000L, 2);
        }
        assertThat(limiter.getCurrentLimit()).isEqualTo(16);

        // 2. Utilized healthy traffic (inFlight = 12 >= 16/2): limit grows additively
        for (int i = 0; i < 12; i++) {
            var res = limiter.tryAcquire();
            assertThat(res.admitted()).isTrue();
            limiter.releaseSuccess(10_000_000L, 12);
        }
        assertThat(limiter.getCurrentLimit()).isGreaterThan(16);
    }

    @Test
    void testLatencyInflation_shouldShrinkLimitAndRecoverWhenLatencyNormalizes() {
        var limiter = new AdaptiveConcurrencyLimiter("rest", 32, 4, 128, 1.5, 0L, 2);

        // Establish 10ms healthy baseline
        for (int i = 0; i < 8; i++) {
            limiter.tryAcquire();
            limiter.releaseSuccess(10_000_000L, 20);
        }
        int healthyLimit = limiter.getCurrentLimit();
        assertThat(healthyLimit).isGreaterThanOrEqualTo(32);

        // Simulate 6x latency inflation (60ms RTT vs 10ms baseline) -> multiplicative decrease
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire();
            limiter.releaseSuccess(60_000_000L, 20);
        }
        int shrunkLimit = limiter.getCurrentLimit();
        assertThat(shrunkLimit).isLessThan(healthyLimit);
        assertThat(shrunkLimit).isGreaterThanOrEqualTo(4);

        // Latency normalizes back to 10ms under high utilization -> additive recovery
        for (int i = 0; i < 24; i++) {
            limiter.tryAcquire();
            limiter.releaseSuccess(10_000_000L, shrunkLimit);
        }
        assertThat(limiter.getCurrentLimit()).isGreaterThan(shrunkLimit);
    }

    @Test
    void testCasAdmission_shouldStrictlyBoundConcurrentSlots() throws Exception {
        var limiter = new AdaptiveConcurrencyLimiter("rest", 4, 1, 64, 1.5, 500L, 2);

        int threads = 50;
        var ready = new CountDownLatch(threads);
        var startGate = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var admitted = new AtomicInteger(0);
        var shed = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        startGate.await(5, TimeUnit.SECONDS);
                        var res = limiter.tryAcquire();
                        if (res.admitted()) {
                            admitted.incrementAndGet();
                            // Keep slot held so all 50 threads race against the 4-slot cap
                            Thread.sleep(100);
                            limiter.releaseSuccess(100_000_000L, res.inFlight());
                        } else {
                            shed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(admitted.get()).isEqualTo(4);
        assertThat(shed.get()).isEqualTo(46);
        assertThat(limiter.getInFlight()).isZero();
    }
}