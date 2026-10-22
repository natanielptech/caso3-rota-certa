package br.com.rotacerta.cotacao.api;

import br.com.rotacerta.cotacao.service.FreightQuoteService;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/cotacoes")
public class FreightQuoteController {
    private final FreightQuoteService freightQuoteService;
    private final StoreRateLimiter rateLimiter;

    public FreightQuoteController(FreightQuoteService freightQuoteService, StoreRateLimiter rateLimiter) {
        this.freightQuoteService = freightQuoteService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public FreightQuoteResponse quote(
            @RequestParam String origem,
            @RequestParam String destino,
            @RequestParam BigDecimal peso,
            @RequestHeader(name = "X-Loja-Id", defaultValue = "anonima") String lojaId) {
        if (origem.isBlank() || destino.isBlank() || peso.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Origem, destino e peso positivo são obrigatórios");
        }
        if (!rateLimiter.allow(lojaId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Limite de 50 cotações por segundo excedido");
        }
        return freightQuoteService.quote(origem, destino, peso);
    }
}
