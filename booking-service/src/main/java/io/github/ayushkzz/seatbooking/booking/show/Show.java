package io.github.ayushkzz.seatbooking.booking.show;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "shows")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String venue;

    @Column(nullable = false)
    private Instant startsAt;

    /** Max users choosing seats at once; null when the show has no waiting room. */
    private Integer waitingRoomCapacity;

    protected Show() {
    }

    public Show(String title, String venue, Instant startsAt, Integer waitingRoomCapacity) {
        this.title = title;
        this.venue = venue;
        this.startsAt = startsAt;
        this.waitingRoomCapacity = waitingRoomCapacity;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Integer getWaitingRoomCapacity() {
        return waitingRoomCapacity;
    }
}
