package io.github.ayushkzz.seatbooking.booking.hold;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Serialises requests that share an idempotency key, so a client retrying while its first request
 * is still running gets a clear 409 instead of racing it. The stored booking (unique per user and
 * key) is what makes completed retries return the original result.
 */
@Component
public class IdempotencyGuard {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private static final RedisScript<Long> UNLOCK = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0""", Long.class);

    private final StringRedisTemplate redis;

    IdempotencyGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Returns a token to pass to {@link #unlock}, or empty if another request holds the key. */
    public Optional<String> tryLock(String userId, String idempotencyKey) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(key(userId, idempotencyKey), token, LOCK_TTL);
        return Boolean.TRUE.equals(ok) ? Optional.of(token) : Optional.empty();
    }

    public void unlock(String userId, String idempotencyKey, String token) {
        redis.execute(UNLOCK, List.of(key(userId, idempotencyKey)), token);
    }

    private static String key(String userId, String idempotencyKey) {
        return "idem:{user:" + userId + "}:" + idempotencyKey;
    }
}
