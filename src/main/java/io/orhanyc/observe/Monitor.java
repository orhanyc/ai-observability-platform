package io.orhanyc.observe;

import java.time.Instant;
import java.util.*;

public final class Monitor {
    public enum Kind { CLASSIFIER, AGENT }
    public enum Status { OPEN, ROLLED_BACK }

    public record Observation(String id, String traceId, String service, Kind kind,
                              String model, String prompt, Instant timestamp, double latencyMs,
                              long costMicros, boolean success, double feature, double prediction) {
        public Observation {
            for (String label : List.of(id, traceId, service, model, prompt)) {
                if (label.isBlank() || label.length() > 100) {
                    throw new IllegalArgumentException("Invalid telemetry label");
                }
            }
            Objects.requireNonNull(kind);
            Objects.requireNonNull(timestamp);
            if (!Double.isFinite(latencyMs) || latencyMs < 0 || costMicros < 0) {
                throw new IllegalArgumentException("Invalid latency or cost");
            }
            for (double value : new double[]{feature, prediction}) {
                if (!Double.isFinite(value) || value < 0 || value > 1) {
                    throw new IllegalArgumentException("Drift signals must be in [0, 1]");
                }
            }
        }
    }

    public record Release(String model, String prompt) {
        static Release of(Observation observation) {
            return new Release(observation.model(), observation.prompt());
        }
    }

    public record Snapshot(int samples, double successRate, double p95Ms, double meanCostMicros,
                           double featureDrift, double predictionDrift) {}

    public record Incident(UUID id, String service, Release release, Instant opened,
                           List<String> reasons, List<Observation> evidence, Status status) {
        public Incident {
            reasons = List.copyOf(reasons);
            evidence = List.copyOf(evidence);
        }
        Incident rolledBack() {
            return new Incident(id, service, release, opened, reasons, evidence, Status.ROLLED_BACK);
        }
    }

    public interface Repository {
        boolean append(Observation observation);
        List<Observation> history(String service);
    }

    public static final class MemoryRepository implements Repository {
        private final int capacity;
        private final LinkedHashMap<String, Observation> entries = new LinkedHashMap<>();

        public MemoryRepository(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
            this.capacity = capacity;
        }

        @Override
        public synchronized boolean append(Observation observation) {
            if (entries.putIfAbsent(observation.id(), observation) != null) return false;
            if (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
            return true;
        }

        @Override
        public synchronized List<Observation> history(String service) {
            return entries.values().stream().filter(row -> row.service().equals(service)).toList();
        }
    }

    private record Deployment(Release stable, Release active) {}
    private record Windows(List<Observation> baseline, List<Observation> current) {}

    private final int window;
    private final Repository repository;
    private final Map<String, Deployment> deployments = new LinkedHashMap<>();
    private final Map<UUID, Incident> incidents = new LinkedHashMap<>();

    public Monitor(Repository repository, int window) {
        if (window < 2) throw new IllegalArgumentException("Window must contain at least two samples");
        this.repository = repository;
        this.window = window;
    }

    public synchronized void configure(String service, Release stable, Release active) {
        if (deployments.putIfAbsent(service, new Deployment(stable, active)) != null) {
            throw new IllegalStateException("Deployment already configured");
        }
    }

    public synchronized boolean ingest(Observation observation) {
        Deployment deployment = deployment(observation.service());
        Release release = Release.of(observation);
        if (!release.equals(deployment.active()) && !release.equals(deployment.stable())) {
            throw new IllegalArgumentException("Unknown release");
        }
        if (!repository.append(observation)) return false;
        Windows windows = windows(observation.service(), deployment);
        if (windows.baseline().size() < window || windows.current().size() < window) return true;
        Snapshot current = summarize(windows);
        List<String> reasons = alerts(current);
        boolean alreadyOpen = incidents.values().stream().anyMatch(incident ->
            incident.service().equals(observation.service()) && incident.status() == Status.OPEN);
        if (!reasons.isEmpty() && !alreadyOpen) {
            UUID id = UUID.randomUUID();
            incidents.put(id, new Incident(id, observation.service(), deployment.active(),
                Instant.now(), reasons, windows.current(), Status.OPEN));
        }
        return true;
    }

    public synchronized Map<String, Snapshot> overview() {
        Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        deployments.forEach((service, deployment) ->
            snapshots.put(service, summarize(windows(service, deployment))));
        return snapshots;
    }

    public synchronized List<Incident> incidents() {
        return List.copyOf(incidents.values());
    }

    public synchronized Incident incident(UUID id) {
        Incident incident = incidents.get(id);
        if (incident == null) throw new NoSuchElementException("Unknown incident");
        return incident;
    }

    public synchronized Release rollback(UUID id) {
        Incident incident = incident(id);
        Deployment deployment = deployment(incident.service());
        if (incident.status() == Status.ROLLED_BACK) return deployment.stable();
        if (!incident.release().equals(deployment.active())) {
            throw new IllegalStateException("Incident release differs from active release");
        }
        deployments.put(incident.service(), new Deployment(deployment.stable(), deployment.stable()));
        incidents.put(id, incident.rolledBack());
        return deployment.stable();
    }

    private Deployment deployment(String service) {
        Deployment deployment = deployments.get(service);
        if (deployment == null) throw new NoSuchElementException("Unknown service");
        return deployment;
    }

    private Windows windows(String service, Deployment deployment) {
        List<Observation> history = repository.history(service);
        if (deployment.stable().equals(deployment.active())) {
            List<Observation> recent = tail(history.stream()
                .filter(row -> Release.of(row).equals(deployment.stable())).toList(), window * 2);
            int split = Math.max(0, recent.size() - window);
            return new Windows(recent.subList(0, split), recent.subList(split, recent.size()));
        }
        return new Windows(
            recentRelease(history, deployment.stable()),
            recentRelease(history, deployment.active()));
    }

    private List<Observation> recentRelease(List<Observation> rows, Release release) {
        return tail(rows.stream().filter(row -> Release.of(row).equals(release)).toList(), window);
    }

    private static List<Observation> tail(List<Observation> rows, int count) {
        return rows.subList(Math.max(0, rows.size() - count), rows.size());
    }

    private static Snapshot summarize(Windows windows) {
        List<Observation> current = windows.current();
        if (current.isEmpty()) return new Snapshot(0, 0, 0, 0, 0, 0);
        double[] latencies = current.stream().mapToDouble(Observation::latencyMs).sorted().toArray();
        return new Snapshot(current.size(),
            current.stream().filter(Observation::success).count() / (double) current.size(),
            latencies[(int) Math.ceil(.95 * latencies.length) - 1],
            current.stream().mapToLong(Observation::costMicros).average().orElse(0),
            drift(windows, Observation::feature), drift(windows, Observation::prediction));
    }

    private static double drift(Windows windows, java.util.function.ToDoubleFunction<Observation> signal) {
        if (windows.baseline().isEmpty()) return 0;
        double[] left = histogram(windows.baseline(), signal);
        double[] right = histogram(windows.current(), signal);
        double distance = 0;
        for (int bin = 0; bin < left.length; bin++) distance += Math.abs(left[bin] - right[bin]);
        return distance / 2;
    }

    private static double[] histogram(List<Observation> rows,
                                      java.util.function.ToDoubleFunction<Observation> signal) {
        double[] bins = new double[10];
        for (Observation row : rows) {
            int bin = Math.min(bins.length - 1, (int) (signal.applyAsDouble(row) * bins.length));
            bins[bin] += 1.0 / rows.size();
        }
        return bins;
    }

    private static List<String> alerts(Snapshot snapshot) {
        List<String> reasons = new ArrayList<>();
        if (snapshot.successRate() < .8) reasons.add("Success rate below 80%");
        if (snapshot.p95Ms() > 500) reasons.add("p95 latency above 500 ms");
        if (snapshot.meanCostMicros() > 1000) reasons.add("Mean cost above 1000 microdollars");
        if (snapshot.featureDrift() > .4) reasons.add("Input distribution shifted");
        if (snapshot.predictionDrift() > .4) reasons.add("Prediction distribution shifted");
        return reasons;
    }
}
