package br.com.rotacerta.cotacao.api;

import java.math.BigDecimal;

public record CarrierQuote(String transportadora, BigDecimal preco, int prazoDias, boolean estimada) {
}
