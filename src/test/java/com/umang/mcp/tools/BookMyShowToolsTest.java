package com.umang.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * Pure unit tests for {@link BookMyShowTools} — no network, no MCP runtime.
 *
 * <p>We build a real {@link WebClient} on a stub {@link ExchangeFunction} that captures the outgoing
 * request (so we can assert the HTTP method + path) and returns a canned {@code ApiResponse} envelope
 * (so we can assert the tools UNWRAP {@code data} correctly and degrade gracefully on errors).</p>
 */
class BookMyShowToolsTest {

    private final String baseUrl = "http://localhost:8080";

    /** Build BookMyShowTools whose WebClient always returns the given JSON body, capturing the request. */
    private BookMyShowTools toolsReturning(String jsonBody, AtomicReference<ClientRequest> captured) {
        ExchangeFunction exchange = request -> {
            captured.set(request);
            ClientResponse response = ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(jsonBody)
                    .build();
            return Mono.just(response);
        };
        WebClient client = WebClient.builder().baseUrl(baseUrl).exchangeFunction(exchange).build();
        return new BookMyShowTools(client);
    }

    /** Build BookMyShowTools whose WebClient always errors (simulates backend down). */
    private BookMyShowTools toolsFailing() {
        ExchangeFunction exchange = request -> Mono.error(new RuntimeException("connection refused"));
        WebClient client = WebClient.builder().baseUrl(baseUrl).exchangeFunction(exchange).build();
        return new BookMyShowTools(client);
    }

    @Test
    void listMovies_hitsMoviesPath_andUnwrapsEnvelope() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String envelope = """
                {"success":true,"data":[
                    {"id":1,"title":"Inception","language":"English","genre":"Sci-Fi"},
                    {"id":2,"title":"Interstellar","language":"English","genre":"Sci-Fi"}
                ],"error":null}
                """;
        BookMyShowTools tools = toolsReturning(envelope, captured);

        String result = tools.listMovies();

        assertThat(captured.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/movies");
        assertThat(result).contains("Inception").contains("Interstellar").contains("id=1");
    }

    @Test
    void listShows_buildsMovieShowsPath() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        BookMyShowTools tools = toolsReturning("{\"success\":true,\"data\":[{\"id\":7,\"startTime\":\"19:00\"}]}", captured);

        String result = tools.listShows(42L);

        assertThat(captured.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/movies/42/shows");
        assertThat(result).contains("showId=7");
    }

    @Test
    void availableSeats_buildsShowSeatsPath() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        BookMyShowTools tools = toolsReturning("{\"success\":true,\"data\":[{\"id\":100,\"seatNumber\":\"A1\"}]}", captured);

        String result = tools.availableSeats(5L);

        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/shows/5/seats");
        assertThat(result).contains("seatId=100").contains("A1");
    }

    @Test
    void bookSeats_postsToInitiate_andUnwrapsBookingId() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String envelope = "{\"success\":true,\"data\":{\"id\":555,\"status\":\"INITIATED\"},\"error\":null}";
        BookMyShowTools tools = toolsReturning(envelope, captured);

        String result = tools.bookSeats(1L, 5L, List.of(100L, 101L));

        assertThat(captured.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/bookings/initiate");
        assertThat(result).contains("bookingId=555").contains("INITIATED");
    }

    @Test
    void bookingStatus_buildsBookingPath_andReadsStatus() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        BookMyShowTools tools = toolsReturning("{\"success\":true,\"data\":{\"status\":\"CONFIRMED\",\"showId\":5}}", captured);

        String result = tools.bookingStatus(12L);

        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/bookings/12");
        assertThat(result).contains("status=CONFIRMED");
    }

    @Test
    void backendDown_returnsFriendlyMessage_doesNotThrow() {
        BookMyShowTools tools = toolsFailing();

        String result = tools.listMovies();

        assertThat(result).contains("unavailable");
    }
}
