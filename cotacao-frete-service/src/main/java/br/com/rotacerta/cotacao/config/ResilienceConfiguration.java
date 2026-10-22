package br.com.rotacerta.cotacao.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CarrierProperties.class)
public class ResilienceConfiguration {
    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(20)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(10)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(attempt -> {
                    long baseDelay = Math.min(100L << (attempt - 1), 400L);
                    long jitter = (long) (baseDelay * 0.5 * Math.random());
                    return baseDelay + jitter;
                })
                .build();
        return RetryRegistry.of(config);
    }

    @Bean
    BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(40)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    @Bean
    TimeLimiterRegistry timeLimiterRegistry(CarrierProperties properties) {
        return TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(properties.getTimeoutMs()))
                .cancelRunningFuture(true)
                .build());
    }

    @Bean
    CarrierResilience carrierResilience(
            CircuitBreakerRegistry circuitBreakers,
            RetryRegistry retries,
            BulkheadRegistry bulkheads,
            TimeLimiterRegistry timeLimiters) {
        ConcurrentHashMap<String, CircuitBreaker> circuits = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Retry> retryPolicies = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Bulkhead> concurrencyLimits = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, TimeLimiter> timeouts = new ConcurrentHashMap<>();
        return new CarrierResilience(
                name -> circuits.computeIfAbsent(name, circuitBreakers::circuitBreaker),
                name -> retryPolicies.computeIfAbsent(name, retries::retry),
                name -> concurrencyLimits.computeIfAbsent(name, bulkheads::bulkhead),
                name -> timeouts.computeIfAbsent(name, timeLimiters::timeLimiter));
    }

    public record CarrierResilience(
            java.util.function.Function<String, CircuitBreaker> circuitBreaker,
            java.util.function.Function<String, Retry> retry,
            java.util.function.Function<String, Bulkhead> bulkhead,
            java.util.function.Function<String, TimeLimiter> timeLimiter) {
    }
}
