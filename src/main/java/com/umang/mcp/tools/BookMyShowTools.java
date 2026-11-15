package com.umang.mcp.tools;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.umang.mcp.http.BackendClient;

@Component
public class BookMyShowTools {

    private static final Logger log = LoggerFactory.getLogger(BookMyShowTools.class);

    private final BackendClient backend;

    public BookMyShowTools(@Qualifier("bookMyShowBackend") BackendClient backend) {
        this.backend = backend;
    }

    @Tool(description = "List all currently active movies playing in BookMyShow. "
            + "Call this when the user asks what movies are playing / showing / available. "
            + "Returns each movie's id, title, language and genre. Use the returned movie id with listShows.")
    public String listMovies() {
        log.info("[tool] listMovies()");
        JsonNode data = backend.getJson("/api/v1/movies");
        if (data == null) {
            return backend.unavailableMessage("fetch the movie list");
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
        JsonNode data = backend.getJson("/api/v1/movies/" + movieId + "/shows");
        if (data == null) {
            return backend.unavailableMessage("fetch shows for movie " + movieId);
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
        JsonNode data = backend.getJson("/api/v1/shows/" + showId + "/seats");
        if (data == null) {
            return backend.unavailableMessage("check seats for show " + showId);
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
        JsonNode data = backend.postJson("/api/v1/bookings/initiate", body);
        if (data == null) {
            return backend.unavailableMessage("initiate the booking");
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
        JsonNode data = backend.getJson("/api/v1/bookings/" + bookingId);
        if (data == null) {
            return backend.unavailableMessage("look up booking " + bookingId);
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
}
