package io.github.ayushkzz.seatbooking.booking.show;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Reads only the columns the seat map needs, without loading entities. */
    @Query("""
            select new io.github.ayushkzz.seatbooking.booking.show.SeatView(s.id, s.label, s.status, s.priceCents)
            from Seat s where s.showId = :showId order by s.id""")
    List<SeatView> seatMap(long showId);

    long countByShowId(long showId);

    long countByShowIdAndIdIn(long showId, Collection<Long> ids);

    @Query("select coalesce(sum(s.priceCents), 0) from Seat s where s.showId = :showId and s.id in :ids")
    long sumPrice(long showId, Collection<Long> ids);

    /**
     * Compare-and-set on seat status. Moves only the seats that are still in {@code from}, so the
     * returned row count tells the caller whether it won every seat. This is the database-level
     * guarantee against double booking, independent of the Redis hold.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Seat s set s.status = :to, s.version = s.version + 1
            where s.showId = :showId and s.id in :ids and s.status = :from""")
    int transition(long showId, Collection<Long> ids, SeatStatus from, SeatStatus to);
}
