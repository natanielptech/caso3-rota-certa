package br.com.rotacerta.pedidos.service;

import br.com.rotacerta.pedidos.api.OrderResponse;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private final ConcurrentHashMap<UUID, OrderResponse> orders = new ConcurrentHashMap<>();

    public OrderResponse save(OrderResponse order) {
        orders.put(order.id(), order);
        return order;
    }

    public Optional<OrderResponse> find(UUID id) {
        return Optional.ofNullable(orders.get(id));
    }
}
