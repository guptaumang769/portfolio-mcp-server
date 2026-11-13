package com.umang.mcp.tools;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * BookMyShow tools, exposed over the Model Context Protocol.
 *
 * <p>Each public method annotated with {@link Tool} becomes an MCP tool. At startup Spring AI turns
 * every {@link Tool} method into a JSON function schema (name + description + typed params) and the
 * Spring AI MCP server (WebMVC/SSE transport) advertises it to any connected MCP host (Claude Desktop,
 * an IDE, a custom agent, ...). When a model — running in that host, not in this process — decides to
 * call a tool, the host sends an MCP {@code tools/call} request, this server deserializes the arguments,
 * invokes the method, and returns the String result over MCP so the model can OBSERVE it and continue.</p>
 *
 * <p>The {@code description} on each {@link Tool} is what the model reads to decide WHEN to call the tool,
 * so it is written for the model, not for humans.</p>
 *
 * <p>These call the real BookMyShow backend over HTTP via a {@link WebClient}. We deliberately do NOT
 * import any BookMyShow classes — the backend is treated as an opaque external HTTP service returning an
 * {@code ApiResponse<T>} envelope {@code {success, data, error}} which we unwrap here into concise,
 * model-friendly Strings. Every method degrades gracefully: if the backend is down we return a friendly
 * "backend unavailable" string rather than throwing, so the host/model can still respond.</p>
 */
@Component
public class BookMyShowTools {

    private static final Logger log = LoggerFactory.getLogger(BookMyShowTools.class);

    private final WebClient webClient;

    public BookMyShowTools(WebClient bookMyShowClient) {
        this.webClient = bookMyShowClient;
    }

    @Tool(description = "List all currently active movies playing in BookMyShow. "
            + "Call this when the user asks what movies are playing / showing / available. "
            + "Returns each movie's id, title, language and genre. Use the returned movie id with listShows.")
    public String listMovies() {
        log.info("[tool] listMovies()");
        JsonNode data = getJson("/api/v1/movies");
        if (data == null) {
            return backendUnavailable("fetch the movie list");
        }
        if (!data.isArray() || data.isEmpty()) {
            return "No active movies are currently listed.";
        }
        StringBuilder sb = new StringBuilder("Now playing:\n");
        for (JsonNode m : data) {
            sb.append("- id=").append(m.path("id").asText("?"))
              .append(" | ").append(m.path("title").asText("Untitled"));
            if (m.hasNonNull("language")) {
                sb.append(" (").append(m.path("language").asText()).append(")");
            }
            if (m.hasNonNull("genre")) {
                sb.append(" [").append(m.path("genre").asText()).append("]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "List the available show times for a specific movie. "
            + "Call this after the user picks a movie. Requires the numeric movieId (from listMovies). "
            + "Returns each show's id, screen/theatre and start time. Use the returned show id with availableSeats or bookSeats.")
    public String listShows(@ToolParam(description = "The numeric id of the movie, as returned by listMovies") long movieId) {
        log.info("[tool] listShows(movieId={})", movieId);
        JsonNode data = getJson("/api/v1/movies/" + movieId + "/shows");
        if (data == null) {
            return backendUnavailable("fetch shows for movie " + movieId);
        }
        if (!data.isArray() || data.isEmpty()) {
            return "No shows found for movie id=" + movieId + ".";
        }
        StringBuilder sb = new StringBuilder("Shows for movie id=" + movieId + ":\n");
        for (JsonNode s : data) {
            sb.append("- showId=").append(s.path("id").asText("?"));
            if (s.hasNonNull("screenName")) {
                sb.append(" | screen=").append(s.path("screenName").asText());
            } else if (s.hasNonNull("theatreName")) {
                sb.append(" | theatre=").append(s.path("theatreName").asText());
            }
            if (s.hasNonNull("startTime")) {
                sb.append(" | starts=").append(s.path("startTime").asText());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Check which seats are still AVAILABLE for a specific show. "
            + "Call this before booking so the user can pick seats, or when the user asks what seats are free. "
            + "Requires the numeric showId (from listShows). Returns available seat ids and labels.")
    public String availableSeats(@ToolParam(description = "The numeric id of the show, as returned by listShows") long showId) {
        log.info("[tool] availableSeats(showId={})", showId);
        JsonNode data = getJson("/api/v1/shows/" + showId + "/seats");
        if (data == null) {
            return backendUnavailable("check seats for show " + showId);
        }
        if (!data.isArray() || data.isEmpty()) {
            return "No available seats for show id=" + showId + " (it may be sold out).";
        }
        StringBuilder sb = new StringBuilder("Available seats for show id=" + showId + ":\n");
        for (JsonNode seat : data) {
            sb.append("- seatId=").append(seat.path("id").asText("?"));
            if (seat.hasNonNull("seatNumber")) {
                sb.append(" (").append(seat.path("seatNumber").asText()).append(")");
            } else if (seat.hasNonNull("label")) {
                sb.append(" (").append(seat.path("label").asText()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Initiate a booking (reserve seats) for a show. This creates a booking in INITIATED state; "
            + "it is NOT paid/confirmed yet. Call this once the user has chosen a show and the specific seat ids to book. "
            + "Requires userId, the numeric showId, and the list of numeric seatIds to reserve. "
            + "Returns the new booking id and its status; share the booking id with the user so they can confirm/pay.")
    public String bookSeats(
            @ToolParam(description = "The id of the user making the booking") long userId,
            @ToolParam(description = "The numeric id of the show to book, as returned by listShows") long showId,
            @ToolParam(description = "The list of numeric seat ids to reserve, as returned by availableSeats") List<Long> seatIds) {
        log.info("[tool] bookSeats(userId={}, showId={}, seatIds={})", userId, showId, seatIds);
        Map<String, Object> body = Map.of(
                "userId", userId,
                "showId", showId,
                "seatIds", seatIds == null ? List.of() : seatIds);
        JsonNode data = postJson("/api/v1/bookings/initiate", body);
        if (data == null) {
            return backendUnavailable("initiate the booking");
        }
        String bookingId = data.path("id").asText(data.path("bookingId").asText("?"));
        String status = data.path("status").asText("INITIATED");
        return "Booking initiated. bookingId=" + bookingId + ", status=" + status
                + ". Seats " + seatIds + " are reserved on show id=" + showId
                + ". The booking is not paid yet — confirm to complete payment.";
    }

    @Tool(description = "Look up the current status/details of an existing booking by its id. "
            + "Call this when the user asks about the state of a booking they already made. Requires the numeric bookingId.")
    public String bookingStatus(@ToolParam(description = "The numeric id of the booking, as returned by bookSeats") long bookingId) {
        log.info("[tool] bookingStatus(bookingId={})", bookingId);
        JsonNode data = getJson("/api/v1/bookings/" + bookingId);
        if (data == null) {
            return backendUnavailable("look up booking " + bookingId);
        }
        String status = data.path("status").asText("UNKNOWN");
        StringBuilder sb = new StringBuilder("Booking id=" + bookingId + " status=" + status);
        if (data.hasNonNull("showId")) {
            sb.append(", showId=").append(data.path("showId").asText());
        }
        if (data.hasNonNull("seatIds")) {
            sb.append(", seats=").append(data.path("seatIds").toString());
        }
        return sb.toString();
    }

    // --- HTTP helpers: call the backend and unwrap the ApiResponse<T> { success, data, error } envelope. ---

    /** GET the path, return the {@code data} node of the ApiResponse envelope, or null if the backend is unreachable. */
    private JsonNode getJson(String path) {
        try {
            JsonNode envelope = webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(ex -> {
                        log.warn("[tool] GET {} failed: {}", path, ex.toString());
                        return Mono.empty();
                    })
                    .block();
            return unwrap(envelope);
        } catch (Exception ex) {
            log.warn("[tool] GET {} threw: {}", path, ex.toString());
            return null;
        }
    }

    /** POST the body to the path, return the {@code data} node of the ApiResponse envelope, or null if unreachable. */
    private JsonNode postJson(String path, Object body) {
        try {
            JsonNode envelope = webClient.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(ex -> {
                        log.warn("[tool] POST {} failed: {}", path, ex.toString());
                        return Mono.empty();
                    })
                    .block();
            return unwrap(envelope);
        } catch (Exception ex) {
            log.warn("[tool] POST {} threw: {}", path, ex.toString());
            return null;
        }
    }

    /**
     * Unwrap the {@code ApiResponse<T> { success, data, error }} envelope. Returns the {@code data} node,
     * or null if the envelope is missing/unsuccessful. If the response is not enveloped (plain body),
     * returns it as-is so the tools still work against a non-enveloped mock.
     */
    private JsonNode unwrap(JsonNode envelope) {
        if (envelope == null || envelope.isNull()) {
            return null;
        }
        if (envelope.has("success") || envelope.has("data")) {
            if (envelope.has("success") && !envelope.path("success").asBoolean(true)) {
                log.warn("[tool] backend returned success=false, error={}", envelope.path("error").toString());
                return null;
            }
            JsonNode data = envelope.get("data");
            return (data == null || data.isNull()) ? null : data;
        }
        return envelope; // not an envelope; use as-is
    }

    private String backendUnavailable(String action) {
        return "The BookMyShow backend is currently unavailable, so I couldn't " + action
                + ". Please make sure the backend is running (default http://localhost:8080) and try again.";
    }
}
