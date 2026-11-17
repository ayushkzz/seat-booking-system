package io.github.ayushkzz.seatbooking.booking.show;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
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
    private final SeatMapService seatMaps;

    ShowController(ShowService shows, SeatMapService seatMaps) {
        this.shows = shows;
        this.seatMaps = seatMaps;
    }

    record CreateShowRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200) String venue,
            @NotNull @Future Instant startsAt,
            @Min(1) @Max(26) int rows,
            @Min(1) @Max(50) int seatsPerRow,
            @PositiveOrZero long priceCents,
            @Min(1) Integer waitingRoomCapacity) {
    }

    /** @param waitingRoomCapacity null when anyone can book without queueing */
    record ShowResponse(long id, String title, String venue, Instant startsAt, long totalSeats,
                        Integer waitingRoomCapacity) {
    }

    @GetMapping
    List<ShowResponse> upcoming() {
        return shows.upcoming().stream().map(this::response).toList();
    }

    @PostMapping
    ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
        Show show = shows.create(request.title(), request.venue(), request.startsAt(), request.rows(),
                request.seatsPerRow(), request.priceCents(), request.waitingRoomCapacity());
        return ResponseEntity.created(URI.create("/api/shows/" + show.getId())).body(response(show));
    }

    @GetMapping("/{showId}")
    ShowResponse get(@PathVariable long showId) {
        return response(shows.get(showId));
    }

    @GetMapping(value = "/{showId}/seats", produces = MediaType.APPLICATION_JSON_VALUE)
    String seats(@PathVariable long showId) {
        return seatMaps.seatMapJson(showId);
    }

    private ShowResponse response(Show show) {
        return new ShowResponse(show.getId(), show.getTitle(), show.getVenue(), show.getStartsAt(),
                shows.seatCount(show.getId()), show.getWaitingRoomCapacity());
    }
}
