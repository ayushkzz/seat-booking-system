package io.github.ayushkzz.seatbooking.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the gateway in front of two stub services and a real Redis (Testcontainers by default,
 * or localhost with ITEST_LOCAL=true).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIT {

    static final boolean LOCAL = Boolean.parseBoolean(System.getenv().getOrDefault("ITEST_LOCAL", "false"));
    static final GenericContainer<?> REDIS =
            LOCAL ? null : new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final HttpServer BOOKING = stub("booking-service");
    static final HttpServer NOTIFICATION = stub("notification-service");

    static {
        if (!LOCAL) {
            REDIS.start();
        }
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("BOOKING_SERVICE_URL", () -> "http://localhost:" + BOOKING.getAddress().getPort());
        registry.add("NOTIFICATION_SERVICE_URL", () -> "http://localhost:" + NOTIFICATION.getAddress().getPort());
        registry.add("RATE_LIMIT_BOOKINGS_PER_SECOND", () -> "1");
        registry.add("RATE_LIMIT_BOOKINGS_BURST", () -> "5");
        if (!LOCAL) {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        }
    }

    @AfterAll
    static void stopStubs() {
        BOOKING.stop(0);
        NOTIFICATION.stop(0);
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void routesTheBookingApiToBookingService() throws Exception {
        var response = send("GET", "/api/shows/1/seats");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("booking-service GET /api/shows/1/seats");
    }

    @Test
    void routesNotificationsToNotificationService() throws Exception {
        var response = send("GET", "/api/users/alice/notifications");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("notification-service GET /api/users/alice/notifications");
    }

    @Test
    void doesNotExposeAnythingOutsideTheApi() throws Exception {
        assertThat(send("GET", "/internal/admin").statusCode()).isEqualTo(404);
    }

    @Test
    void limitsHowFastOneClientCanCreateBookings() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        HttpResponse<String> first = null;
        for (int i = 0; i < 20; i++) {
            var response = send("POST", "/api/shows/1/bookings");
            first = first == null ? response : first;
            statuses.add(response.statusCode());
        }

        // burst of 5, refilled at 1 per second: a few requests pass, the rest are rejected
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("X-RateLimit-Burst-Capacity")).hasValue("5");
        assertThat(statuses).filteredOn(status -> status == 200).hasSizeBetween(5, 8);
        assertThat(statuses).filteredOn(status -> status == 429).hasSizeGreaterThanOrEqualTo(12);
        assertThat(statuses).containsOnly(200, 429);

        // the stricter booking limit does not use up the general limit for reads
        assertThat(send("GET", "/api/shows/1/seats").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A tiny upstream that answers every request with its own name, the method and the path. */
    private static HttpServer stub(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = (name + " " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
