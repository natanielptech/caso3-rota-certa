package br.com.rotacerta.pedidos.api;

import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String status,
        FreightQuoteResponse cotacao,
        Instant criadoEm) {
}
