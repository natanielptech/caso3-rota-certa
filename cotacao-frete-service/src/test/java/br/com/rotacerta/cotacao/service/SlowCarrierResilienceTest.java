package br.com.rotacerta.cotacao.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rotacerta.cotacao.api.FreightQuoteResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SlowCarrierResilienceTest {
    private static HttpServer partnerServer;
    private static ExecutorService partnerExecutor;
    private static final AtomicReference<String> observedTraceparent = new AtomicReference<>();

    @Autowired
    private FreightQuoteService quoteService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeAll
    static void startPartners() throws IOException {
        ensurePartnersStarted();
    }

    private static void ensurePartnersStarted() throws IOException {
        if (partnerServer != null) {
            return;
        }
        partnerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        partnerExecutor = Executors.newCachedThreadPool();
        partnerServer.setExecutor(partnerExecutor);
        partnerServer.createContext("/transvale", exchange -> {
            observedTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            try {
                TimeUnit.SECONDS.sleep(30);
                respond(exchange, "{\"transportadora\":\"transvale\",\"preco\":12.00,\"prazoDias\":3}");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        partnerServer.createContext("/rapida", exchange -> {
            observedTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            respond(exchange, "{\"transportadora\":\"rapida\",\"preco\":15.00,\"prazoDias\":2}");
        });
        partnerServer.createContext("/economica", exchange -> {
            observedTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            respond(exchange, "{\"transportadora\":\"economica\",\"preco\":10.00,\"prazoDias\":4}");
        });
        partnerServer.start();
    }

    @DynamicPropertySource
    static void carrierUrls(DynamicPropertyRegistry registry) throws IOException {
        ensurePartnersStarted();
        String baseUrl = "http://127.0.0.1:" + partnerServer.getAddress().getPort();
        registry.add("rotacerta.carriers.urls.transvale", () -> baseUrl + "/transvale");
        registry.add("rotacerta.carriers.urls.rapida", () -> baseUrl + "/rapida");
        registry.add("rotacerta.carriers.urls.economica", () -> baseUrl + "/economica");
    }

    @AfterAll
    static void stopPartners() {
        partnerServer.stop(0);
        partnerExecutor.shutdownNow();
    }

    @Test
    void slowCarrierTimesOutOpensCircuitAndUsesReferenceRate() {
        List<FreightQuoteResponse> responses = java.util.stream.IntStream.range(0, 20)
                .parallel()
                .mapToObj(index -> quoteService.quote("SP", "RJ", new BigDecimal("2.0")))
                .toList();

        assertThat(circuitBreakers.circuitBreaker("transvale").getState().name()).isEqualTo("OPEN");
        assertThat(responses)
                .allSatisfy(response -> assertThat(response.cotacoes())
                        .anySatisfy(quote -> {
                            assertThat(quote.transportadora()).isEqualTo("transvale");
                            assertThat(quote.estimada()).isTrue();
                        }));
        assertThat(responses.get(0).cotacoes()).anyMatch(quote -> "rapida".equals(quote.transportadora())
                && !quote.estimada());
        assertThat(observedTraceparent.get()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
