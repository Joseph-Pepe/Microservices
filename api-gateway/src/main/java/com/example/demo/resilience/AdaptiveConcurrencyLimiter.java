package com.example.demo.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency-limits style Gradient2 Adaptive Concurrency Limiter (Little's Law).
 * - Lock-free CAS admission guarantees inFlight never overshoots currentLimit under Virtual Thread bursts.
 * - Asymmetric EWMA longRtt prevents baseline drift during sustained downstream saturation.
 * - App-limited guard prevents inflating currentLimit when traffic is idle.
 */
public class AdaptiveConcurrencyLimiter {

    private final String partitionName;
    private final int initialLimit;
    private final int minLimit;
    private final int maxLimit;
    private final double rttTolerance;
    private final long windowDurationNanos;
    private final int retryAfterSeconds;

    private final AtomicInteger currentLimit;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    private final Object windowLock = new Object();
    private long windowStartNanos;
    private long windowRttSumNanos = 0L;
    private long windowSampleCount = 0L;
    private int windowMaxInFlight = 0;
    private double longRttNanos = 0.0;

    private final AtomicLong totalAdmitted = new AtomicLong(0L);
    private final AtomicLong totalShed = new AtomicLong(0L);

    public AdaptiveConcurrencyLimiter(
            String partitionName,
            int initialLimit,
            int minLimit,
            int maxLimit,
            double rttTolerance,
            long windowMs,
            int retryAfterSeconds) {
        if (minLimit < 1 || initialLimit < minLimit || maxLimit < initialLimit) {
            throw new IllegalArgumentException("Invalid concurrency limits: require 1 <= min <= initial <= max");
        }
        if (rttTolerance < 1.0) {
            throw new IllegalArgumentException("rttTolerance must be >= 1.0");
        }
        this.partitionName = partitionName;
        this.initialLimit = initialLimit;
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.rttTolerance = rttTolerance;
        this.windowDurationNanos = Math.max(0L, windowMs) * 1_000_000L;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.currentLimit = new AtomicInteger(initialLimit);
        this.windowStartNanos = System.nanoTime();
    }

    public AdmissionResult tryAcquire() {
        while (true) {
            int active = inFlight.get();
            int limit = currentLimit.get();
            if (active >= limit) {
                totalShed.incrementAndGet();
                return new AdmissionResult(false, limit, active);
            }
            if (inFlight.compareAndSet(active, active + 1)) {
                totalAdmitted.incrementAndGet();
                return new AdmissionResult(true, limit, active + 1);
            }
        }
    }

    public void releaseSuccess(long rttNanos, int observedInFlight) {
        try {
            recordSample(Math.max(1L, rttNanos), observedInFlight, false);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public void releaseDropped(long rttNanos, int observedInFlight) {
        try {
            recordSample(Math.max(1L, rttNanos), observedInFlight, true);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public void releaseIgnored() {
        inFlight.decrementAndGet();
    }

    private void recordSample(long rttNanos, int observedInFlight, boolean overloaded) {
        long nowNanos = System.nanoTime();
        synchronized (windowLock) {
            if (overloaded && longRttNanos > 0.0) {
                // Force overload RTT sample at least 2x baseline so Gradient2 shrinks cleanly on timeouts/504s
                long penalizedRtt = (long) Math.max(rttNanos, longRttNanos * rttTolerance * 1.5);
                windowRttSumNanos += penalizedRtt;
            } else {
                windowRttSumNanos += rttNanos;
            }
            windowSampleCount++;
            if (observedInFlight > windowMaxInFlight) {
                windowMaxInFlight = observedInFlight;
            }

            if (windowSampleCount < 4L || (windowDurationNanos > 0L && (nowNanos - windowStartNanos) < windowDurationNanos)) {
                return;
            }

            double shortRtt = (double) windowRttSumNanos / (double) windowSampleCount;
            if (longRttNanos <= 0.0) {
                longRttNanos = shortRtt;
            } else if (shortRtt < longRttNanos) {
                // Fast downward recovery toward healthy latency baseline
                longRttNanos = (0.8 * longRttNanos) + (0.2 * shortRtt);
            } else {
                // Very slow upward drift so sustained overload cannot disguise queueing delay
                longRttNanos = (0.98 * longRttNanos) + (0.02 * shortRtt);
            }

            int oldLimit = currentLimit.get();
            double toleratedBaseline = longRttNanos * rttTolerance;
            int newLimit;

            if (shortRtt <= toleratedBaseline) {
                // Healthy RTT: additive increase only if concurrency was meaningfully utilized (app-limited guard)
                if (windowMaxInFlight >= Math.max(1, oldLimit / 2)) {
                    int queueHeadroom = Math.max(1, (int) Math.sqrt(oldLimit));
                    newLimit = Math.min(maxLimit, oldLimit + queueHeadroom);
                } else {
                    newLimit = oldLimit;
                }
            } else {
                // Queueing detected: multiplicative decrease via Gradient2
                double gradient = Math.max(0.5, Math.min(1.0, toleratedBaseline / shortRtt));
                newLimit = Math.max(minLimit, (int) Math.floor(oldLimit * gradient));
            }

            currentLimit.set(newLimit);

            // Reset sample window
            windowStartNanos = nowNanos;
            windowRttSumNanos = 0L;
            windowSampleCount = 0L;
            windowMaxInFlight = 0;
        }
    }

    public int getCurrentLimit() {
        return currentLimit.get();
    }

    public int getInFlight() {
        return inFlight.get();
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getPartitionName() {
        return partitionName;
    }

    public long getTotalAdmitted() {
        return totalAdmitted.get();
    }

    public long getTotalShed() {
        return totalShed.get();
    }

    public void overrideLimitForTesting(int limit) {
        currentLimit.set(limit);
    }

    public void reset() {
        synchronized (windowLock) {
            currentLimit.set(initialLimit);
            inFlight.set(0);
            windowStartNanos = System.nanoTime();
            windowRttSumNanos = 0L;
            windowSampleCount = 0L;
            windowMaxInFlight = 0;
            longRttNanos = 0.0;
            totalAdmitted.set(0L);
            totalShed.set(0L);
        }
    }

    public record AdmissionResult(boolean admitted, int limit, int inFlight) {
    }
}