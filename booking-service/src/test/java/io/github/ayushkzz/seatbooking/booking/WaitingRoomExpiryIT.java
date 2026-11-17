package io.github.ayushkzz.seatbooking.booking;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "booking.waiting-room.session-ttl=PT2S",
        "booking.waiting-room.heartbeat-ttl=PT2S",
        "booking.waiting-room.admit-interval=PT0.2S"})
class WaitingRoomExpiryIT extends AbstractIntegrationTest {

    @Test
    void aTurnThatRunsOutGoesToTheNextUser() {
        long show = createShow(1, 2, 10000, 1);
        Long seat = seatIds(show).getFirst();
        String idle = joinQueue(show, "mia").get("ticketId").asString();
        String next = joinQueue(show, "ned").get("ticketId").asString();

        // mia never books; ned keeps checking his place and gets in when her turn ends
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(body(queueStatus(show, next)).get("status").asString()).isEqualTo("ADMITTED"));

        assertThat(bookWithTicket(show, "mia", idle, seat).getStatusCode().value()).isEqualTo(403);
        assertThat(bookWithTicket(show, "ned", next, seat).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void aWaitingUserWhoStopsCheckingInIsSkipped() throws InterruptedException {
        long show = createShow(1, 2, 10000, 1);
        joinQueue(show, "oli"); // holds the only place until the turn runs out
        String gone = joinQueue(show, "pam").get("ticketId").asString();
        String stillHere = joinQueue(show, "quin").get("ticketId").asString();

        // pam closes the tab; quin keeps polling
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(body(queueStatus(show, stillHere)).get("status").asString()).isEqualTo("ADMITTED"));

        assertThat(queueStatus(show, gone).getStatusCode().value()).isEqualTo(404);
    }
}
