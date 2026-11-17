package io.github.ayushkzz.seatbooking.booking.waitingroom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.ayushkzz.seatbooking.booking.api.ApiException;
import io.github.ayushkzz.seatbooking.booking.config.BookingProperties;
import io.github.ayushkzz.seatbooking.booking.show.ShowRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * A virtual waiting room for high-demand shows. Only a fixed number of users (the show's capacity)
 * may be choosing seats at once; everyone else waits in a first-come, first-served queue and is
 * let in as places free up. This keeps a rush from turning into thousands of users fighting over
 * the same seats at the same instant.
 *
 * <p>All state lives in Redis, so any instance can serve any request:
 * <ul>
 *   <li>{@code waiting}: sorted set of tickets, ordered by arrival number</li>
 *   <li>{@code admitted}: sorted set of tickets, scored by when their turn ends</li>
 *   <li>{@code ticket:<id>}: the ticket's owner. While waiting it expires unless the user keeps
 *       checking their position, so people who closed the tab don't take up a place</li>
 * </ul>
 * Admission is one atomic Lua script, so two instances can never admit more users than the capacity.
 */
@Service
public class WaitingRoomService {

    private static final String ACTIVE_SHOWS = "wr:active-shows";
    private static final Duration USER_INDEX_TTL = Duration.ofHours(6);

    /**
     * KEYS: waiting, admitted. ARGV: now (ms), capacity, session length (ms), ticket key prefix.
     * Ends expired turns, then admits from the front of the queue until the room is full,
     * skipping tickets whose owner stopped checking in. Returns how many users were admitted.
     */
    private static final RedisScript<Long> ADMIT = RedisScript.of("""
            local now, capacity, session = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            local free = capacity - redis.call('ZCARD', KEYS[2])
            local admitted = 0
            while free > 0 do
              local head = redis.call('ZPOPMIN', KEYS[1])
              if #head == 0 then break end
              local ticket = ARGV[4] .. head[1]
              if redis.call('EXISTS', ticket) == 1 then
                redis.call('ZADD', KEYS[2], now + session, head[1])
                redis.call('PEXPIRE', ticket, session)
                free = free - 1
                admitted = admitted + 1
              end
            end
            return admitted""", Long.class);

    private final StringRedisTemplate redis;
    private final ShowRepository shows;
    private final BookingProperties.WaitingRoom props;
    private final Clock clock;
    private final Counter joined;
    private final Counter admitted;
    private final AtomicLong waitingNow = new AtomicLong();
    private final AtomicLong admittedNow = new AtomicLong();
    /** Capacity per show; a show's capacity never changes, so it is safe to remember. 0 = no waiting room. */
    private final ConcurrentHashMap<Long, Integer> capacities = new ConcurrentHashMap<>();

    WaitingRoomService(StringRedisTemplate redis, ShowRepository shows, BookingProperties props, Clock clock,
                       MeterRegistry metrics) {
        this.redis = redis;
        this.shows = shows;
        this.props = props.waitingRoom();
        this.clock = clock;
        this.joined = Counter.builder("waitingroom.joined").register(metrics);
        this.admitted = Counter.builder("waitingroom.admitted").register(metrics);
        Gauge.builder("waitingroom.users", waitingNow, AtomicLong::get).tag("state", "waiting").register(metrics);
        Gauge.builder("waitingroom.users", admittedNow, AtomicLong::get).tag("state", "admitted").register(metrics);
    }

    /** Joins the queue, or returns the user's existing place if they are already in it. */
    public QueueTicket join(long showId, String userId) {
        int capacity = requireWaitingRoom(showId);
        String userKey = userKey(showId, userId);
        UUID ticketId = UUID.randomUUID();
        while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(userKey, ticketId.toString(), USER_INDEX_TTL))) {
            Optional<QueueTicket> existing = Optional.ofNullable(redis.opsForValue().get(userKey))
                    .flatMap(id -> status(showId, UUID.fromString(id)));
            if (existing.isPresent()) {
                return existing.get();
            }
            redis.delete(userKey); // the old ticket is gone: start again at the back of the queue
        }
        Long arrival = redis.opsForValue().increment(key(showId, "arrivals"));
        redis.opsForValue().set(ticketKey(showId, ticketId), userId, props.heartbeatTtl());
        redis.opsForZSet().add(key(showId, "waiting"), ticketId.toString(), arrival);
        redis.opsForSet().add(ACTIVE_SHOWS, String.valueOf(showId));
        joined.increment();
        admit(showId, capacity);
        return status(showId, ticketId).orElseThrow(ApiException::queueTicketNotFound);
    }

    /** The ticket's current place. Checking it also tells the queue that the user is still there. */
    public Optional<QueueTicket> status(long showId, UUID ticketId) {
        String ticket = ticketId.toString();
        Double turnEnds = redis.opsForZSet().score(key(showId, "admitted"), ticket);
        if (turnEnds != null && turnEnds > clock.millis()) {
            return Optional.of(new QueueTicket(ticketId, QueueTicket.Status.ADMITTED, null,
                    Instant.ofEpochMilli(turnEnds.longValue())));
        }
        Long rank = redis.opsForZSet().rank(key(showId, "waiting"), ticket);
        if (rank == null) {
            return Optional.empty();
        }
        if (!Boolean.TRUE.equals(redis.expire(ticketKey(showId, ticketId), props.heartbeatTtl()))) {
            // the user was away for too long and lost their place
            redis.opsForZSet().remove(key(showId, "waiting"), ticket);
            return Optional.empty();
        }
        return Optional.of(new QueueTicket(ticketId, QueueTicket.Status.WAITING, rank + 1, null));
    }

    /** Gives up the place, whether waiting or admitted, and lets the next user in. */
    public void leave(long showId, UUID ticketId) {
        int capacity = requireWaitingRoom(showId);
        remove(showId, ticketId);
        admit(showId, capacity);
    }

    /**
     * Checks that the user may book now. Returns the admitted ticket for shows with a waiting
     * room, or empty for shows without one.
     */
    public Optional<UUID> requireAdmission(long showId, String userId, String queueTicket) {
        if (capacityOf(showId) == 0) {
            return Optional.empty();
        }
        if (queueTicket == null || queueTicket.isBlank()) {
            throw ApiException.admissionRequired();
        }
        UUID ticketId;
        try {
            ticketId = UUID.fromString(queueTicket.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.notAdmitted();
        }
        Double turnEnds = redis.opsForZSet().score(key(showId, "admitted"), ticketId.toString());
        boolean admittedNow = turnEnds != null && turnEnds > clock.millis();
        if (!admittedNow || !userId.equals(redis.opsForValue().get(ticketKey(showId, ticketId)))) {
            throw ApiException.notAdmitted();
        }
        return Optional.of(ticketId);
    }

    /** Ends an admitted user's turn after they booked, so the next user can come in. */
    public void endSession(long showId, UUID ticketId) {
        remove(showId, ticketId);
        admit(showId, capacityOf(showId));
    }

    /** Fills free places in every waiting room that has users. Called on a schedule. */
    void admitWaiting() {
        Set<String> active = redis.opsForSet().members(ACTIVE_SHOWS);
        long waiting = 0;
        long inside = 0;
        for (String id : active == null ? Set.<String>of() : active) {
            long showId = Long.parseLong(id);
            admit(showId, capacityOf(showId));
            Long showWaiting = redis.opsForZSet().zCard(key(showId, "waiting"));
            Long showInside = redis.opsForZSet().zCard(key(showId, "admitted"));
            if (showWaiting == 0 && showInside == 0) {
                redis.opsForSet().remove(ACTIVE_SHOWS, id);
            }
            waiting += showWaiting;
            inside += showInside;
        }
        waitingNow.set(waiting);
        admittedNow.set(inside);
    }

    private void admit(long showId, int capacity) {
        if (capacity == 0) {
            return;
        }
        Long count = redis.execute(ADMIT, List.of(key(showId, "waiting"), key(showId, "admitted")),
                String.valueOf(clock.millis()), String.valueOf(capacity),
                String.valueOf(props.sessionTtl().toMillis()), key(showId, "ticket:"));
        if (count != null && count > 0) {
            admitted.increment(count);
        }
    }

    private void remove(long showId, UUID ticketId) {
        String ticket = ticketId.toString();
        String owner = redis.opsForValue().get(ticketKey(showId, ticketId));
        redis.opsForZSet().remove(key(showId, "waiting"), ticket);
        redis.opsForZSet().remove(key(showId, "admitted"), ticket);
        redis.delete(ticketKey(showId, ticketId));
        if (owner != null) {
            redis.delete(userKey(showId, owner));
        }
    }

    private int requireWaitingRoom(long showId) {
        int capacity = capacityOf(showId);
        if (capacity == 0) {
            throw ApiException.noWaitingRoom(showId);
        }
        return capacity;
    }

    private int capacityOf(long showId) {
        return capacities.computeIfAbsent(showId, id -> {
            Integer capacity = shows.findById(id).orElseThrow(() -> ApiException.showNotFound(id))
                    .getWaitingRoomCapacity();
            return capacity == null ? 0 : capacity;
        });
    }

    /** All keys of a show share the {show:id} hash tag, so they live in one Redis Cluster slot. */
    private static String key(long showId, String name) {
        return "wr:{show:" + showId + "}:" + name;
    }

    private static String ticketKey(long showId, UUID ticketId) {
        return key(showId, "ticket:") + ticketId;
    }

    private static String userKey(long showId, String userId) {
        return key(showId, "user:") + userId;
    }
}
