package br.com.rotacerta.pedidos.api;

import java.util.List;

public record FreightQuoteResponse(CarrierQuote melhorCotacao, List<CarrierQuote> cotacoes) {
}
