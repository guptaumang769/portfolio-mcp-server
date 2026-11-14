# Diagrams — portfolio-mcp-server

Visual companion to the [README](./README.md). Two Mermaid diagrams: the MCP architecture, and a
single tool call end-to-end.

## 1. MCP architecture

How an MCP host (e.g. Claude Desktop) reaches the BookMyShow + Uber backends through this server.
The host owns the model; **this** server owns the tools; the backends own the data.

```mermaid
flowchart LR
    subgraph Host["MCP Host (e.g. Claude Desktop / IDE / agent)"]
        LLM["LLM / model"]
        Client["MCP client"]
        LLM <--> Client
    end

    subgraph Server["portfolio-mcp-server (Spring AI MCP, :8093)"]
        Transport["SSE transport\nGET /sse, POST /mcp/message"]
        Registry["ToolCallbackProvider\n(10 registered tools)"]
        BMS["BookMyShowTools\n(5 tools)"]
        Uber["UberTools\n(5 tools)"]
        Transport --> Registry
        Registry --> BMS
        Registry --> Uber
    end

    subgraph Backends["Backends (opaque HTTP, :8080)"]
        BMSApi["BookMyShow API\n/api/v1/movies, /shows, /bookings"]
        UberApi["Uber API\n/api/v1/pricing, /locations, /rides, /trips"]
    end

    Client -- "MCP over SSE\n(tools/list, tools/call)" --> Transport
    BMS -- "WebClient (REST)" --> BMSApi
    Uber -- "WebClient (REST)" --> UberApi
```

## 2. A single tool call, end-to-end

The host asks the model, the model decides to call a tool over MCP, this server invokes the tool
which calls the backend, and the result flows back so the model can answer.

```mermaid
sequenceDiagram
    actor User
    participant Host as MCP Host (Claude Desktop)
    participant Model as LLM
    participant MCP as portfolio-mcp-server
    participant Backend as BookMyShow / Uber API

    User->>Host: "What movies are playing?"
    Host->>Model: prompt + advertised MCP tools
    Model-->>Host: tool call: listMovies()
    Host->>MCP: tools/call listMovies (over SSE)
    MCP->>Backend: GET /api/v1/movies
    Backend-->>MCP: ApiResponse{ success, data:[...] }
    MCP-->>Host: unwrapped text result ("Now playing: ...")
    Host->>Model: tool result
    Model-->>Host: natural-language answer
    Host-->>User: "Now playing: Inception, Interstellar, ..."
```
