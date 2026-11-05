package io.github.ayushkzz.seatbooking.booking.hold;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fast first line of defence against double booking. Holds every requested seat in one atomic Lua
 * script (all or nothing), so competing requests are rejected in Redis before they reach the
 * database. Keys carry a {show:id} hash tag so all seats of a show live in the same Redis Cluster slot.
 */
@Component
public class SeatHoldService {

    private static final RedisScript<Long> HOLD = RedisScript.of("""
            for _, key in ipairs(KEYS) do
              if redis.call('EXISTS', key) == 1 then return 0 end
            end
            for _, key in ipairs(KEYS) do
              redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
            end
            return 1""", Long.class);

    /** Only deletes holds that still belong to this booking. */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local released = 0
            for _, key in ipairs(KEYS) do
              if redis.call('GET', key) == ARGV[1] then
                released = released + redis.call('DEL', key)
              end
            end
            return released""", Long.class);

    private final StringRedisTemplate redis;

    SeatHoldService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean hold(long showId, Collection<Long> seatIds, UUID bookingId, Duration ttl) {
        Long ok = redis.execute(HOLD, keys(showId, seatIds), bookingId.toString(), String.valueOf(ttl.toMillis()));
        return ok != null && ok == 1L;
    }

    public void release(long showId, Collection<Long> seatIds, UUID bookingId) {
        redis.execute(RELEASE, keys(showId, seatIds), bookingId.toString());
    }

    static String key(long showId, long seatId) {
        return "hold:{show:" + showId + "}:seat:" + seatId;
    }

    private static List<String> keys(long showId, Collection<Long> seatIds) {
        return seatIds.stream().sorted().map(id -> key(showId, id)).toList();
    }
}
