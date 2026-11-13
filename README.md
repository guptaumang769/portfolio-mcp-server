# portfolio-mcp-server

An **MCP (Model Context Protocol) server** that exposes the BookMyShow and Uber backends as tools any
MCP-capable model can call — Claude Desktop, an IDE, or a custom agent. Built with **Spring Boot 3.3.5 /
Java 21** and **Spring AI 1.0.0** (`spring-ai-starter-mcp-server-webmvc`).

> Diagrams: see [DIAGRAMS.md](./DIAGRAMS.md) for the architecture flowchart and a tool-call sequence.

---

## What is MCP?

**MCP is an open standard for connecting AI models to tools and data** — think of it as **"USB-C for AI
tools."** Instead of every model vendor inventing its own way to plug in your integrations, MCP defines
one protocol. You write your integration **once** as an MCP server, and **any** MCP-capable model can
discover and use it.

The model works in three roles:

- **Host** — the app the user talks to (Claude Desktop, an IDE, an agent). It runs the model.
- **Client** — the MCP connector inside the host that speaks the protocol to a server.
- **Server** — a process (like **this one**) that advertises **tools** (and optionally resources and
  prompts) and executes them when asked.

At connect time the client asks the server `tools/list`; the server returns each tool's name, description
and JSON schema. When the model decides to use one, the host sends `tools/call`, the server runs it, and
returns the result for the model to read and answer with.

### Why it matters

- **Write once, use everywhere.** These 10 BookMyShow + Uber tools are defined a single time here; any
  MCP host can consume them without bespoke glue code.
- **Model-agnostic.** The same tools work regardless of which model the host runs.
- **Clean separation.** The model/host owns reasoning; this server owns the tools; the backends own the
  data. This server imports nothing from the backends — it just calls their REST APIs.

---

## What this server exposes (10 tools)

All tools unwrap the backends' `ApiResponse<T> { success, data, error }` envelope into concise,
model-friendly strings and **degrade gracefully** — if a backend is down they return a friendly
"backend unavailable" message instead of throwing.

### BookMyShow (`BookMyShowTools`)

| Tool | Backend call | Purpose |
|------|--------------|---------|
| `listMovies()` | `GET /api/v1/movies` | List active movies |
| `listShows(movieId)` | `GET /api/v1/movies/{movieId}/shows` | Show times for a movie |
| `availableSeats(showId)` | `GET /api/v1/shows/{showId}/seats` | Free seats for a show |
| `bookSeats(userId, showId, seatIds)` | `POST /api/v1/bookings/initiate` | Reserve seats (INITIATED) |
| `bookingStatus(bookingId)` | `GET /api/v1/bookings/{bookingId}` | Status of a booking |

### Uber (`UberTools`)

| Tool | Backend call | Purpose |
|------|--------------|---------|
| `fareEstimate(pickupLat, pickupLng, dropLat, dropLng)` | `GET /api/v1/pricing/estimate?...` | Estimate fare |
| `nearbyDrivers(lat, lng, radiusKm)` | `GET /api/v1/locations/nearby?...` | Drivers near a point |
| `requestRide(riderId, pickupLat, pickupLng, dropLat, dropLng)` | `POST /api/v1/rides` | Book a ride |
| `rideStatus(rideId)` | `GET /api/v1/rides/{rideId}` | Status of a ride request |
| `tripStatus(tripId)` | `GET /api/v1/trips/{tripId}` | Status of a trip |

Each tool is a `@Tool`-annotated method (`org.springframework.ai.tool.annotation.Tool`); Spring AI turns
each into a JSON function schema. `McpConfig` registers both tool objects with the MCP server via
`MethodToolCallbackProvider.builder().toolObjects(bookMyShowTools, uberTools).build()`, returned as a
`ToolCallbackProvider` bean that the Spring AI MCP auto-configuration advertises.

---

## How to run it

Requires **JDK 21** (Temurin or Corretto) and **Maven**.

```bash
git clone https://github.com/guptaumang769/portfolio-mcp-server.git
cd portfolio-mcp-server

JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn spring-boot:run
```

On Windows (PowerShell): `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21"; mvn spring-boot:run`.

The server starts on **`http://localhost:8093`** with the **SSE transport** enabled:

- `GET  /sse`          — the SSE stream a host connects to
- `POST /mcp/message`  — where the client posts tool-call requests
- `GET  /actuator/health` — health check

For **live** tool calls, start the backends on `:8080`:

- BookMyShow backend (`book-my-show-backend`) — or set `BOOKMYSHOW_BASE_URL`
- Uber platform api-gateway (`uber-platform`) — or set `UBER_BASE_URL`

If the backends are not running, the tools still work and simply report the backend as unavailable
(they never throw), so you can wire up and exercise the MCP plumbing standalone.

### Docker

```bash
docker build -t portfolio-mcp-server .
docker run -p 8093:8093 \
  -e BOOKMYSHOW_BASE_URL=http://host.docker.internal:8080 \
  -e UBER_BASE_URL=http://host.docker.internal:8080 \
  portfolio-mcp-server
```

---

## How to connect it (Claude Desktop)

This build uses the **HTTP/SSE transport**, so point Claude Desktop at the running server's `/sse`
endpoint. Add an entry to `claude_desktop_config.json`
(macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "portfolio": {
      "url": "http://localhost:8093/sse"
    }
  }
}
```

Some Claude Desktop builds only launch **stdio** servers directly and reach an HTTP/SSE server through a
bridge such as `mcp-remote`:

```json
{
  "mcpServers": {
    "portfolio": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8093/sse"]
    }
  }
}
```

Restart Claude Desktop, then ask e.g. *"What movies are playing?"* or *"Estimate an Uber fare from
12.97,77.59 to 13.0,77.6."* — the model will call the corresponding MCP tool on this server.

### Running over stdio instead

To have a host **launch this server as a stdio process** (no web server), set
`spring.ai.mcp.server.stdio=true` (and quiet the web layer/banner), then use the `command`/`args` form of
the config to run the jar directly. The default in this project is HTTP/SSE so it behaves like a normal
web service on `:8093`.

---

## Where this fits — the AI trio

This completes a trio of AI front-ends over the same BookMyShow + Uber backends:

- **`bookmyshow-ai-concierge`** — a Spring AI agent that calls the BookMyShow tools *directly* in-process.
- **`uber-ai-assistant`** — a Spring AI agent that calls the Uber tools *directly* in-process.
- **`portfolio-mcp-server`** (this project) — exposes the *same* tools over **MCP**, the **model-agnostic**
  way: rather than one agent hard-wiring the tools, any MCP host/model can discover and call them.

The agents show tools wired into a specific app; this server shows the same tools published as a reusable,
standard interface.

---

## Project layout

```
src/main/java/com/umang/mcp/
  McpServerApplication.java        # Spring Boot entry point
  config/WebClientConfig.java      # bookMyShowClient + uberClient WebClient beans
  config/McpConfig.java            # ToolCallbackProvider bean registering all 10 tools
  tools/BookMyShowTools.java       # 5 @Tool methods over the BookMyShow API
  tools/UberTools.java             # 5 @Tool methods over the Uber API
src/main/resources/application.yml # port 8093, spring.ai.mcp.server.*, backend base URLs
src/test/java/com/umang/mcp/tools/ # BookMyShowToolsTest + UberToolsTest (pure Mockito/WebClient stubs)
Dockerfile / .dockerignore         # multi-stage build, non-root, healthcheck
DIAGRAMS.md                        # Mermaid architecture + sequence diagrams
```

## Tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
```

Pure unit tests — no network, no MCP runtime. Each tool is exercised against a real `WebClient` backed by
a stub `ExchangeFunction` that captures the outgoing request (asserting method + path + query) and returns
a canned `ApiResponse` envelope (asserting the tool unwraps `data`). A failing exchange verifies the
graceful "unavailable" path.
