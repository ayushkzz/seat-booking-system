package io.github.ayushkzz.seatbooking.booking.show;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, Long> {

    List<Show> findTop100ByStartsAtAfterOrderByStartsAt(Instant after);
}
