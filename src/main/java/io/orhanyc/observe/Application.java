package io.orhanyc.observe;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    Monitor monitor(@Value("${observe.capacity:5000}") int capacity) {
        Monitor monitor = new Monitor(new Monitor.MemoryRepository(capacity), Demo.WINDOW);
        Demo.load(monitor);
        return monitor;
    }
}

@RestController
@RequestMapping("/api")
class ObservabilityController {
    private final Monitor monitor;
    private final io.opentelemetry.api.trace.Tracer tracer =
        GlobalOpenTelemetry.getTracer("io.orhanyc.observe");

    ObservabilityController(Monitor monitor) {
        this.monitor = monitor;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/overview")
    Object overview() {
        return monitor.overview();
    }

    @GetMapping("/incidents")
    Object incidents() {
        return monitor.incidents();
    }

    @GetMapping("/incidents/{id}")
    Object replay(@PathVariable UUID id) {
        return monitor.incident(id);
    }

    @PostMapping("/incidents/{id}/rollback")
    Object rollback(@PathVariable UUID id) {
        return monitor.rollback(id);
    }

    @PostMapping("/observations")
    Map<String, Boolean> ingest(@RequestBody Monitor.Observation observation) {
        var span = tracer.spanBuilder("telemetry.ingest").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("demo.service", observation.service());
            return Map.of("accepted", monitor.ingest(observation));
        } catch (RuntimeException error) {
            span.setStatus(StatusCode.ERROR);
            throw error;
        } finally {
            span.end();
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> missing(NoSuchElementException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(RuntimeException error) {
        return Map.of("error", error.getMessage());
    }
}
