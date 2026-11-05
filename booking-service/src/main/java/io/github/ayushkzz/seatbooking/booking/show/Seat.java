package io.github.ayushkzz.seatbooking.booking.show;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long showId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private long priceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Version
    private long version;

    protected Seat() {
    }

    public Seat(Long showId, String label, long priceCents) {
        this.showId = showId;
        this.label = label;
        this.priceCents = priceCents;
    }

    public Long getId() {
        return id;
    }

    public Long getShowId() {
        return showId;
    }

    public String getLabel() {
        return label;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public SeatStatus getStatus() {
        return status;
    }
}
