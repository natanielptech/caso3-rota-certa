package br.com.rotacerta.pedidos.service;

import br.com.rotacerta.pedidos.api.FreightQuoteResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FreightQuoteClient {
    private final RestClient client;

    public FreightQuoteClient(RestClient.Builder builder, QuoteClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.timeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.timeoutMs()));
        this.client = builder.baseUrl(properties.url()).requestFactory(requestFactory).build();
    }

    public FreightQuoteResponse quote(String storeId, String origin, String destination, double weight) {
        return client.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/cotacoes")
                        .queryParam("origem", origin)
                        .queryParam("destino", destination)
                        .queryParam("peso", weight)
                        .build())
                .header("X-Loja-Id", storeId)
                .retrieve()
                .body(FreightQuoteResponse.class);
    }
}

record QuoteClientProperties(String url, long timeoutMs) {
}

@Configuration
class FreightQuoteClientConfiguration {
    @Bean
    QuoteClientProperties quoteClientProperties(
            @Value("${rotacerta.quote-service.url}") String url,
            @Value("${rotacerta.quote-service.timeout-ms:1200}") long timeoutMs) {
        return new QuoteClientProperties(url, timeoutMs);
    }
}
