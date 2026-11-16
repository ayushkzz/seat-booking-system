package io.github.ayushkzz.seatbooking.booking.show;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Renders the seat map of a show as JSON, served from the Redis cache when possible. */
@Service
public class SeatMapService {

    record SeatMapResponse(long showId, Map<SeatStatus, Long> summary, List<SeatView> seats) {
    }

    private final ShowService shows;
    private final SeatMapCache cache;
    private final ObjectMapper json;

    SeatMapService(ShowService shows, SeatMapCache cache, ObjectMapper json) {
        this.shows = shows;
        this.cache = cache;
        this.json = json;
    }

    public String seatMapJson(long showId) {
        return cache.get(showId).orElseGet(() -> {
            String rendered = render(showId);
            cache.put(showId, rendered);
            return rendered;
        });
    }

    private String render(long showId) {
        List<SeatView> seats = shows.seatMap(showId);
        Map<SeatStatus, Long> summary = seats.stream().collect(
                Collectors.groupingBy(SeatView::status, () -> new EnumMap<>(SeatStatus.class), Collectors.counting()));
        return json.writeValueAsString(new SeatMapResponse(showId, summary, seats));
    }
}
