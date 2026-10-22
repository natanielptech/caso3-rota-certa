package br.com.rotacerta.pedidos.service;

import br.com.rotacerta.pedidos.api.CreateOrderRequest;
import br.com.rotacerta.pedidos.api.FreightQuoteResponse;
import br.com.rotacerta.pedidos.api.OrderResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class OrderService {
    private final FreightQuoteClient freightQuoteClient;
    private final OrderRepository repository;

    public OrderService(FreightQuoteClient freightQuoteClient, OrderRepository repository) {
        this.freightQuoteClient = freightQuoteClient;
        this.repository = repository;
    }

    public OrderResponse create(CreateOrderRequest request) {
        try {
            FreightQuoteResponse quote = freightQuoteClient.quote(
                    request.lojaId(), request.origem(), request.destino(), request.peso().doubleValue());
            return repository.save(new OrderResponse(UUID.randomUUID(), "CONFIRMADO", quote, Instant.now()));
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Cotação indisponível dentro de 1,2 s", ex);
        }
    }

    public Optional<OrderResponse> find(UUID id) {
        return repository.find(id);
    }
}
