package com.umang.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Pure unit tests for {@link UberTools} — no network, no MCP runtime.
 *
 * <p>Same approach as {@link BookMyShowToolsTest}: a real {@link WebClient} on a stub
 * {@link ExchangeFunction} captures the outgoing request (method + path + query) and returns a canned
 * {@code ApiResponse} envelope, so we can assert path building, query params, envelope unwrapping, and
 * graceful-on-error behaviour.</p>
 */
class UberToolsTest {

    private final String baseUrl = "http://localhost:8080";

    private UberTools toolsReturning(String jsonBody, AtomicReference<ClientRequest> captured) {
        ExchangeFunction exchange = request -> {
            captured.set(request);
            ClientResponse response = ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(jsonBody)
                    .build();
            return Mono.just(response);
        };
        WebClient client = WebClient.builder().baseUrl(baseUrl).exchangeFunction(exchange).build();
        return new UberTools(client);
    }

    private UberTools toolsFailing() {
        ExchangeFunction exchange = request -> Mono.error(new RuntimeException("connection refused"));
        WebClient client = WebClient.builder().baseUrl(baseUrl).exchangeFunction(exchange).build();
        return new UberTools(client);
    }

    @Test
    void fareEstimate_hitsEstimatePath_withCoordsQuery_andUnwraps() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String envelope = "{\"success\":true,\"data\":{\"distanceKm\":4.2,\"surgeMultiplier\":1.5,\"fare\":180},\"error\":null}";
        UberTools tools = toolsReturning(envelope, captured);

        String result = tools.fareEstimate(12.97, 77.59, 13.0, 77.6);

        assertThat(captured.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/pricing/estimate");
        String query = captured.get().url().getQuery();
        assertThat(query).contains("pickupLat=12.97").contains("pickupLng=77.59")
                .contains("dropLat=13.0").contains("dropLng=77.6");
        assertThat(result).contains("4.2 km").contains("1.5x").contains("180");
    }

    @Test
    void nearbyDrivers_hitsNearbyPath_withRadiusQuery_andUnwrapsArray() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String envelope = "{\"success\":true,\"data\":[{\"driverId\":9,\"distanceKm\":0.8},{\"driverId\":10,\"distanceKm\":1.4}]}";
        UberTools tools = toolsReturning(envelope, captured);

        String result = tools.nearbyDrivers(12.97, 77.59, 5.0);

        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/locations/nearby");
        String query = captured.get().url().getQuery();
        assertThat(query).contains("lat=12.97").contains("lng=77.59").contains("radiusKm=5.0");
        assertThat(result).contains("2 driver(s)").contains("driverId=9").contains("0.8 km away");
    }

    @Test
    void requestRide_postsToRides_andUnwrapsRideId() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String envelope = "{\"success\":true,\"data\":{\"id\":777,\"status\":\"REQUESTED\"},\"error\":null}";
        UberTools tools = toolsReturning(envelope, captured);

        String result = tools.requestRide(1L, 12.97, 77.59, 13.0, 77.6);

        assertThat(captured.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/rides");
        assertThat(result).contains("rideId=777").contains("REQUESTED");
    }

    @Test
    void rideStatus_buildsRidePath_andReadsStatus() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        UberTools tools = toolsReturning("{\"success\":true,\"data\":{\"status\":\"MATCHED\",\"driverId\":9,\"tripId\":55}}", captured);

        String result = tools.rideStatus(777L);

        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/rides/777");
        assertThat(result).contains("status=MATCHED").contains("driverId=9").contains("tripId=55");
    }

    @Test
    void tripStatus_buildsTripPath_andReadsStatus() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        UberTools tools = toolsReturning("{\"success\":true,\"data\":{\"status\":\"IN_PROGRESS\",\"driverId\":9,\"fare\":180}}", captured);

        String result = tools.tripStatus(55L);

        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/trips/55");
        assertThat(result).contains("status=IN_PROGRESS").contains("driverId=9").contains("180");
    }

    @Test
    void backendDown_returnsFriendlyMessage_doesNotThrow() {
        UberTools tools = toolsFailing();

        String result = tools.fareEstimate(12.97, 77.59, 13.0, 77.6);

        assertThat(result).contains("unavailable");
    }
}
