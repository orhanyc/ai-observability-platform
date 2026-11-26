package io.orhanyc.observe;

import java.time.Instant;

public final class Demo {
    public static final int WINDOW = 12;
    private static final Monitor.Release STABLE = new Monitor.Release("v1", "p1");
    private static final Monitor.Release CANDIDATE = new Monitor.Release("v2", "p2");

    private Demo() {}

    static void load(Monitor monitor) {
        seed(monitor, "support-agent", Monitor.Kind.AGENT, true);
        seed(monitor, "intent-classifier", Monitor.Kind.CLASSIFIER, false);
    }

    private static void seed(Monitor monitor, String service, Monitor.Kind kind, boolean regression) {
        monitor.configure(service, STABLE, CANDIDATE);
        Instant start = Instant.now().minusSeconds(WINDOW * 2L);
        for (int i = 0; i < WINDOW * 2; i++) {
            Monitor.Release release = i < WINDOW ? STABLE : CANDIDATE;
            boolean degraded = regression && i >= WINDOW;
            monitor.ingest(new Monitor.Observation(
                service + ":" + i, service + ":trace:" + i, service, kind,
                release.model(), release.prompt(), start.plusSeconds(i),
                (degraded ? 800 : 120) + i, degraded ? 2200 : 120,
                !degraded || i % 3 == 0, degraded ? .8 : .2, degraded ? .2 : .8
            ));
        }
    }

    public static void main(String[] args) {
        Monitor monitor = new Monitor(new Monitor.MemoryRepository(500), WINDOW);
        load(monitor);
        if (monitor.incidents().size() != 1) throw new AssertionError("Expected one incident");
        var incident = monitor.incidents().get(0);
        if (!incident.service().equals("support-agent") || incident.evidence().size() != WINDOW) {
            throw new AssertionError("Expected replayable agent incident");
        }
        if (!monitor.rollback(incident.id()).equals(STABLE)
            || monitor.overview().get("support-agent").successRate() != 1
            || monitor.incident(incident.id()).status() != Monitor.Status.ROLLED_BACK) {
            throw new AssertionError("Expected stable release after rollback");
        }
        System.out.println("PASS: seeded telemetry, incident replay, and simulated rollback");
    }
}
