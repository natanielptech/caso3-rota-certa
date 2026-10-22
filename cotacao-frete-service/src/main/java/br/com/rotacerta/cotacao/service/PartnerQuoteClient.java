package br.com.rotacerta.cotacao.service;

import br.com.rotacerta.cotacao.api.CarrierQuote;
import br.com.rotacerta.cotacao.config.CarrierProperties;
import br.com.rotacerta.cotacao.config.ResilienceConfiguration.CarrierResilience;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class PartnerQuoteClient {
    private final CarrierProperties properties;
    private final CarrierResilience resilience;
    private final ReferenceRateTable rateTable;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;
    private final java.util.Map<String, HttpClient> clients;
    private final java.util.Map<String, ExecutorService> executors;

    public PartnerQuoteClient(
            CarrierProperties properties,
            CarrierResilience resilience,
            ReferenceRateTable rateTable,
            ObjectMapper objectMapper,
            Tracer tracer,
            Propagator propagator) {
        this.properties = properties;
        this.resilience = resilience;
        this.rateTable = rateTable;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
        this.executors = new java.util.concurrent.ConcurrentHashMap<>();
        this.clients = new java.util.concurrent.ConcurrentHashMap<>();
        properties.getUrls().keySet().forEach(name -> {
            ExecutorService executor = Executors.newFixedThreadPool(40, runnable -> {
                Thread thread = new Thread(runnable, "partner-" + name);
                thread.setDaemon(true);
                return thread;
            });
            executors.put(name, executor);
            clients.put(name, HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .executor(executor)
                    .build());
        });
    }

    public CarrierQuote quote(String carrier, String origin, String destination, BigDecimal weight) {
        try {
            return resilience.circuitBreaker().apply(carrier).executeSupplier(() ->
                    resilience.retry().apply(carrier).executeSupplier(() ->
                            resilience.bulkhead().apply(carrier).executeSupplier(() ->
                                    fetchWithTimeout(carrier, origin, destination, weight))));
        } catch (CallNotPermittedException | BulkheadFullException ex) {
            return rateTable.quote(carrier, weight);
        } catch (RuntimeException ex) {
            return rateTable.quote(carrier, weight);
        }
    }

    private CarrierQuote fetchWithTimeout(String carrier, String origin, String destination, BigDecimal weight) {
        Span span = tracer.nextSpan().name("carrier.quote").tag("peer.service", carrier).start();
        CompletableFuture<CarrierQuote> future = null;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            String url = properties.getUrls().get(carrier);
            if (url == null) {
                throw new IllegalArgumentException("URL não configurada para " + carrier);
            }
            String target = url + "?origem=" + encode(origin) + "&destino=" + encode(destination) + "&peso=" + weight;
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .GET();
            propagator.inject(span.context(), requestBuilder, (builder, key, value) -> builder.header(key, value));
            CompletableFuture<CarrierQuote> pending = clients.get(carrier)
                    .sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            throw new IllegalStateException("Transportadora retornou HTTP " + response.statusCode());
                        }
                        try {
                            return objectMapper.readValue(response.body(), CarrierQuote.class);
                        } catch (IOException ex) {
                            throw new IllegalStateException("Resposta inválida da transportadora", ex);
                        }
                    });
            future = pending;
            return resilience.timeLimiter().apply(carrier).executeFutureSupplier(() -> pending);
        } catch (InterruptedException ex) {
            if (future != null) {
                future.cancel(true);
            }
            Thread.currentThread().interrupt();
            span.error(ex);
            throw new IllegalStateException("Chamada interrompida para " + carrier, ex);
        } catch (ExecutionException ex) {
            if (future != null) {
                future.cancel(true);
            }
            span.error(ex);
            throw new IllegalStateException("Falha na transportadora " + carrier, ex);
        } catch (Exception ex) {
            if (future != null) {
                future.cancel(true);
            }
            span.error(ex);
            throw new IllegalStateException("Tempo limite excedido na transportadora " + carrier, ex);
        } finally {
            span.end();
        }
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executors.values().forEach(executor -> {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
