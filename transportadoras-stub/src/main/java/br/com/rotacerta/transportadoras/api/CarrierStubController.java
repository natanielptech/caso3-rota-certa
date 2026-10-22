package br.com.rotacerta.transportadoras.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/partners")
public class CarrierStubController {
    private static final Map<String, BigDecimal> BASE_PRICES = Map.of(
            "transvale", new BigDecimal("12.00"),
            "rapida", new BigDecimal("15.00"),
            "economica", new BigDecimal("10.00"));
    private final Map<String, Long> delays;
    private final Map<String, Boolean> failures;

    public CarrierStubController(
            @Value("${rotacerta.stub.delay-ms.transvale:0}") long transvaleDelay,
            @Value("${rotacerta.stub.delay-ms.rapida:0}") long rapidaDelay,
            @Value("${rotacerta.stub.delay-ms.economica:0}") long economicaDelay,
            @Value("${rotacerta.stub.fail.transvale:false}") boolean transvaleFailure,
            @Value("${rotacerta.stub.fail.rapida:false}") boolean rapidaFailure,
            @Value("${rotacerta.stub.fail.economica:false}") boolean economicaFailure) {
        this.delays = Map.of("transvale", transvaleDelay, "rapida", rapidaDelay, "economica", economicaDelay);
        this.failures = Map.of("transvale", transvaleFailure, "rapida", rapidaFailure, "economica", economicaFailure);
    }

    @GetMapping("/{carrier}")
    public CarrierQuote quote(
            @PathVariable String carrier,
            @RequestParam BigDecimal peso) {
        if (!BASE_PRICES.containsKey(carrier)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transportadora desconhecida");
        }
        try {
            TimeUnit.MILLISECONDS.sleep(delays.get(carrier));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chamada interrompida");
        }
        if (failures.get(carrier)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Falha simulada da transportadora");
        }
        BigDecimal price = BASE_PRICES.get(carrier)
                .add(peso.multiply(new BigDecimal("1.75")))
                .setScale(2, RoundingMode.HALF_UP);
        return new CarrierQuote(carrier, price, 2 + carrier.length() % 4);
    }

    public record CarrierQuote(String transportadora, BigDecimal preco, int prazoDias) {
    }
}
