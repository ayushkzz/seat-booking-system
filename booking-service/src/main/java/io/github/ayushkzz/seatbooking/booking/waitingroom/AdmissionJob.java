package io.github.ayushkzz.seatbooking.booking.waitingroom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lets waiting users in as places free up. Places are also filled immediately when someone joins,
 * books or leaves; this job covers turns that simply ran out of time.
 */
@Component
class AdmissionJob {

    private static final Logger log = LoggerFactory.getLogger(AdmissionJob.class);

    private final WaitingRoomService waitingRoom;

    AdmissionJob(WaitingRoomService waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

    @Scheduled(fixedDelayString = "${booking.waiting-room.admit-interval}")
    void admitWaitingUsers() {
        try {
            waitingRoom.admitWaiting();
        } catch (RuntimeException e) {
            log.warn("Waiting room admission failed, will retry: {}", e.getMessage());
        }
    }
}
