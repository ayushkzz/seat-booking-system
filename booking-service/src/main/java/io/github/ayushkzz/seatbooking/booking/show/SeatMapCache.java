package io.github.ayushkzz.seatbooking.booking.show;

import java.util.Optional;

import io.github.ayushkzz.seatbooking.booking.config.BookingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caches the rendered seat map of a show in Redis for a short time (one second by default).
 *
 * <p>Seat maps are read far more often than seats change, and during a rush the same map is
 * requested many times per second. Evicting the entry on every booking would make most reads miss
 * exactly when the cache matters, so entries simply expire. A seat map can therefore be slightly
 * out of date, which is fine: it is only a view, and every booking is checked against the Redis
 * holds and PostgreSQL.
 */
@Component
public class SeatMapCache {

    private final StringRedisTemplate redis;
    private final BookingProperties props;
    private final Counter hits;
    private final Counter misses;

    SeatMapCache(StringRedisTemplate redis, BookingProperties props, MeterRegistry metrics) {
        this.redis = redis;
        this.props = props;
        this.hits = Counter.builder("seatmap.cache.requests").tag("result", "hit").register(metrics);
        this.misses = Counter.builder("seatmap.cache.requests").tag("result", "miss").register(metrics);
    }

    Optional<String> get(long showId) {
        Optional<String> cached = Optional.ofNullable(redis.opsForValue().get(key(showId)));
        (cached.isPresent() ? hits : misses).increment();
        return cached;
    }

    void put(long showId, String json) {
        redis.opsForValue().set(key(showId), json, props.seatMapCacheTtl());
    }

    private static String key(long showId) {
        return "seatmap:{show:" + showId + "}";
    }
}
