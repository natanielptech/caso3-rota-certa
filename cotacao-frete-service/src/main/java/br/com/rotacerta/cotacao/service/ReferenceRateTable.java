package br.com.rotacerta.cotacao.service;

import br.com.rotacerta.cotacao.api.CarrierQuote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReferenceRateTable {
    private static final Map<String, BigDecimal> PRICE_PER_KILO = Map.of(
            "transvale", new BigDecimal("2.40"),
            "rapida", new BigDecimal("2.65"),
            "economica", new BigDecimal("2.20"));

    public CarrierQuote quote(String carrier, BigDecimal weight) {
        BigDecimal perKilo = PRICE_PER_KILO.getOrDefault(carrier, new BigDecimal("2.50"));
        BigDecimal price = weight.multiply(perKilo).add(new BigDecimal("9.90"))
                .setScale(2, RoundingMode.HALF_UP);
        return new CarrierQuote(carrier, price, 5, true);
    }
}
