package waymoreitems.core;

import java.util.Arrays;
import java.util.Locale;

/**
 * Durées mesurées (en nanosecondes) sur une fenêtre : nombre, moyenne, médiane, 95e centile et maximum.
 * Les percentiles portent sur les {@link #CAPACITY} dernières valeurs ; nombre, moyenne et maximum sur toutes.
 * Non synchronisé : à n'utiliser que depuis un seul fil (le fil de rendu).
 */
public final class TimingStats {
    public static final int CAPACITY = 8192;

    private final long[] samples = new long[CAPACITY];
    private int stored;
    private int next;
    private long count;
    private long total;
    private long max;

    public void add(long nanos) {
        if (nanos < 0) {
            return;
        }
        samples[next] = nanos;
        next = (next + 1) % CAPACITY;
        if (stored < CAPACITY) {
            stored++;
        }
        count++;
        total += nanos;
        if (nanos > max) {
            max = nanos;
        }
    }

    public void reset() {
        stored = 0;
        next = 0;
        count = 0;
        total = 0;
        max = 0;
    }

    public long count() {
        return count;
    }

    public long totalNanos() {
        return total;
    }

    public double meanMillis() {
        return count == 0 ? 0 : total / (double) count / 1_000_000.0;
    }

    public double maxMillis() {
        return max / 1_000_000.0;
    }

    /** Centile {@code p} (0 à 100) des valeurs conservées, en millisecondes ; 0 sans valeur. */
    public double percentileMillis(double p) {
        if (stored == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(samples, stored);
        Arrays.sort(copy);
        int rank = (int) Math.ceil(Math.max(0, Math.min(100, p)) / 100.0 * stored) - 1;
        return copy[Math.max(0, Math.min(stored - 1, rank))] / 1_000_000.0;
    }

    /** Résumé lisible : {@code n=120 mean=0.412 p50=0.380 p95=0.700 max=1.900 ms}. */
    public String summary() {
        return String.format(Locale.ROOT, "n=%d mean=%.3f p50=%.3f p95=%.3f max=%.3f ms",
                count, meanMillis(), percentileMillis(50), percentileMillis(95), maxMillis());
    }
}
