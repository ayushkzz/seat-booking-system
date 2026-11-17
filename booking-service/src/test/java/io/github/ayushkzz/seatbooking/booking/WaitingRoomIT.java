package io.github.ayushkzz.seatbooking.booking;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class WaitingRoomIT extends AbstractIntegrationTest {

    @Test
    void letsInOnlyAsManyUsersAsTheShowAllowsAndQueuesTheRestInOrder() {
        long show = createShow(1, 5, 10000, 2);

        List<JsonNode> tickets = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            tickets.add(joinQueue(show, "user-" + i));
        }

        assertThat(tickets).extracting(t -> t.get("status").asString())
                .containsExactly("ADMITTED", "ADMITTED", "WAITING", "WAITING", "WAITING");
        assertThat(tickets.get(0).get("expiresAt").isNull()).isFalse();
        assertThat(tickets.subList(2, 5)).extracting(t -> t.get("position").asInt()).containsExactly(1, 2, 3);
    }

    @Test
    void bookingEndsTheTurnAndAdmitsTheNextUserInLine() {
        long show = createShow(1, 3, 10000, 1);
        List<Long> seats = seatIds(show);
        String first = joinQueue(show, "ana").get("ticketId").asString();
        String second = joinQueue(show, "ben").get("ticketId").asString();
        String third = joinQueue(show, "cy").get("ticketId").asString();

        assertThat(bookWithTicket(show, "ana", first, seats.get(0)).getStatusCode().value()).isEqualTo(201);

        assertThat(body(queueStatus(show, second)).get("status").asString()).isEqualTo("ADMITTED");
        assertThat(body(queueStatus(show, third)).get("position").asInt()).isEqualTo(1);
        // the turn is used up: the same ticket cannot book again
        var again = bookWithTicket(show, "ana", first, seats.get(1));
        assertThat(again.getStatusCode().value()).isEqualTo(403);
        assertThat(body(again).get("code").asString()).isEqualTo("not-admitted");
    }

    @Test
    void bookingIsRefusedWithoutAnAdmittedTicket() {
        long show = createShow(1, 2, 10000, 1);
        Long seat = seatIds(show).getFirst();
        String admitted = joinQueue(show, "dee").get("ticketId").asString();
        String waiting = joinQueue(show, "eli").get("ticketId").asString();

        var noTicket = bookWithTicket(show, "eli", null, seat);
        assertThat(noTicket.getStatusCode().value()).isEqualTo(403);
        assertThat(body(noTicket).get("code").asString()).isEqualTo("admission-required");

        assertThat(bookWithTicket(show, "eli", waiting, seat).getStatusCode().value()).isEqualTo(403);
        assertThat(bookWithTicket(show, "eli", "not-a-ticket", seat).getStatusCode().value()).isEqualTo(403);
        // someone else's admitted ticket does not work either
        assertThat(bookWithTicket(show, "eli", admitted, seat).getStatusCode().value()).isEqualTo(403);

        assertThat(bookWithTicket(show, "dee", admitted, seat).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void joiningAgainKeepsTheSamePlace() {
        long show = createShow(1, 2, 10000, 1);
        joinQueue(show, "fay");
        JsonNode first = joinQueue(show, "gus");
        joinQueue(show, "hal");

        JsonNode again = joinQueue(show, "gus");

        assertThat(again.get("ticketId")).isEqualTo(first.get("ticketId"));
        assertThat(again.get("position").asInt()).isEqualTo(1);
    }

    @Test
    void leavingTheQueueMovesEveryoneBehindForward() {
        long show = createShow(1, 2, 10000, 1);
        String inside = joinQueue(show, "ivy").get("ticketId").asString();
        String next = joinQueue(show, "jon").get("ticketId").asString();
        String last = joinQueue(show, "kim").get("ticketId").asString();

        http().delete().uri("/api/shows/{id}/queue/{ticket}", show, next).retrieve().toBodilessEntity();
        assertThat(queueStatus(show, next).getStatusCode().value()).isEqualTo(404);
        assertThat(body(queueStatus(show, last)).get("position").asInt()).isEqualTo(1);

        http().delete().uri("/api/shows/{id}/queue/{ticket}", show, inside).retrieve().toBodilessEntity();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(body(queueStatus(show, last)).get("status").asString()).isEqualTo("ADMITTED"));
    }

    @Test
    void showsWithoutAWaitingRoomCanBeBookedDirectly() {
        long show = createShow(1, 1, 10000);
        Long seat = seatIds(show).getFirst();

        var join = http().post().uri("/api/shows/{id}/queue", show).contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("userId", "lou")).retrieve().toEntity(String.class);
        assertThat(join.getStatusCode().value()).isEqualTo(400);
        assertThat(body(join).get("code").asString()).isEqualTo("no-waiting-room");

        assertThat(bookWithTicket(show, "lou", null, seat).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void listsUpcomingShowsWithTheirWaitingRoomCapacity() {
        long show = createShow(1, 1, 10000, 7);

        JsonNode shows = body(http().get().uri("/api/shows").retrieve().toEntity(String.class));

        assertThat(shows.valueStream().filter(s -> s.get("id").asLong() == show).findFirst())
                .hasValueSatisfying(s -> assertThat(s.get("waitingRoomCapacity").asInt()).isEqualTo(7));
    }
}
