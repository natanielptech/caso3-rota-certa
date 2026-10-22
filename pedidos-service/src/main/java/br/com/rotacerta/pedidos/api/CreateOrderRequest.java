package br.com.rotacerta.pedidos.api;

import java.math.BigDecimal;

public record CreateOrderRequest(String lojaId, String origem, String destino, BigDecimal peso) {
}
