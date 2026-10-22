package br.com.rotacerta.cotacao.service;

import br.com.rotacerta.cotacao.api.CarrierQuote;
import br.com.rotacerta.cotacao.api.FreightQuoteResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class FreightQuoteService {
    private static final List<String> CARRIERS = List.of("transvale", "rapida", "economica");
    private final PartnerQuoteClient partnerQuoteClient;

    public FreightQuoteService(PartnerQuoteClient partnerQuoteClient) {
        this.partnerQuoteClient = partnerQuoteClient;
    }

    public FreightQuoteResponse quote(String origin, String destination, BigDecimal weight) {
        List<CompletableFuture<CarrierQuote>> requests = CARRIERS.stream()
                .map(carrier -> CompletableFuture.supplyAsync(
                        () -> partnerQuoteClient.quote(carrier, origin, destination, weight)))
                .toList();
        List<CarrierQuote> quotes = requests.stream().map(CompletableFuture::join).toList();
        CarrierQuote best = Stream.concat(
                        quotes.stream().filter(quote -> !quote.estimada()),
                        quotes.stream().filter(CarrierQuote::estimada))
                .min((left, right) -> left.preco().compareTo(right.preco()))
                .orElseThrow();
        return new FreightQuoteResponse(best, quotes);
    }
}
