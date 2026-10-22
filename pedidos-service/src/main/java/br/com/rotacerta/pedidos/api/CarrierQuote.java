package br.com.rotacerta.pedidos.api;

import java.math.BigDecimal;

public record CarrierQuote(String transportadora, BigDecimal preco, int prazoDias, boolean estimada) {
}
