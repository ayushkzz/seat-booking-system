package io.github.ayushkzz.seatbooking.booking.show;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shows")
class ShowController {

    private final ShowService shows;

    ShowController(ShowService shows) {
        this.shows = shows;
    }

    record CreateShowRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200) String venue,
            @NotNull @Future Instant startsAt,
            @Min(1) @Max(26) int rows,
            @Min(1) @Max(50) int seatsPerRow,
            @PositiveOrZero long priceCents) {
    }

    record ShowResponse(long id, String title, String venue, Instant startsAt, long totalSeats) {
    }

    record SeatView(long id, String label, SeatStatus status, long priceCents) {
    }

    record SeatMapResponse(long showId, Map<SeatStatus, Long> summary, List<SeatView> seats) {
    }

    @PostMapping
    ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
        Show show = shows.create(request.title(), request.venue(), request.startsAt(), request.rows(),
                request.seatsPerRow(), request.priceCents());
        return ResponseEntity.created(URI.create("/api/shows/" + show.getId())).body(response(show));
    }

    @GetMapping("/{showId}")
    ShowResponse get(@PathVariable long showId) {
        return response(shows.get(showId));
    }

    @GetMapping("/{showId}/seats")
    SeatMapResponse seats(@PathVariable long showId) {
        List<SeatView> seats = shows.seatMap(showId).stream()
                .map(s -> new SeatView(s.getId(), s.getLabel(), s.getStatus(), s.getPriceCents()))
                .toList();
        Map<SeatStatus, Long> summary = seats.stream()
                .collect(Collectors.groupingBy(SeatView::status, () -> new java.util.EnumMap<>(SeatStatus.class),
                        Collectors.counting()));
        return new SeatMapResponse(showId, summary, seats);
    }

    private ShowResponse response(Show show) {
        return new ShowResponse(show.getId(), show.getTitle(), show.getVenue(), show.getStartsAt(),
                shows.seatCount(show.getId()));
    }
}
