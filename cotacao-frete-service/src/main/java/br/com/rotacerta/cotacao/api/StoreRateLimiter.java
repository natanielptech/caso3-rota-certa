package br.com.rotacerta.cotacao.api;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class StoreRateLimiter {
    private static final int LIMIT_PER_SECOND = 50;
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final int MAX_STORES = 10_000;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String storeId) {
        long now = System.nanoTime();
        if (!windows.containsKey(storeId) && windows.size() >= MAX_STORES) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_NANOS);
            if (windows.size() >= MAX_STORES) {
                return false;
            }
        }
        Window window = windows.compute(storeId, (key, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_NANOS) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });
        return window.count <= LIMIT_PER_SECOND;
    }

    private record Window(long startedAt, int count) {
    }
}
