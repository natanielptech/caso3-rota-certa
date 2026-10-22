package br.com.rotacerta.cotacao.api;

import java.util.List;

public record FreightQuoteResponse(CarrierQuote melhorCotacao, List<CarrierQuote> cotacoes) {
}
