package io.github.ayushkzz.seatbooking.booking.show;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.ayushkzz.seatbooking.booking.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private final ShowRepository shows;
    private final SeatRepository seats;

    ShowService(ShowRepository shows, SeatRepository seats) {
        this.shows = shows;
        this.seats = seats;
    }

    /** Creates a show with a rectangular seat map: rows A, B, C... and seats 1..seatsPerRow. */
    @Transactional
    public Show create(String title, String venue, Instant startsAt, int rows, int seatsPerRow, long priceCents) {
        Show show = shows.save(new Show(title, venue, startsAt));
        List<Seat> layout = new ArrayList<>(rows * seatsPerRow);
        for (int row = 0; row < rows; row++) {
            for (int number = 1; number <= seatsPerRow; number++) {
                layout.add(new Seat(show.getId(), (char) ('A' + row) + String.valueOf(number), priceCents));
            }
        }
        seats.saveAll(layout);
        return show;
    }

    @Transactional(readOnly = true)
    public Show get(long showId) {
        return shows.findById(showId).orElseThrow(() -> ApiException.showNotFound(showId));
    }

    @Transactional(readOnly = true)
    public List<Seat> seatMap(long showId) {
        if (!shows.existsById(showId)) {
            throw ApiException.showNotFound(showId);
        }
        return seats.findByShowIdOrderById(showId);
    }

    @Transactional(readOnly = true)
    public long seatCount(long showId) {
        return seats.countByShowId(showId);
    }
}
