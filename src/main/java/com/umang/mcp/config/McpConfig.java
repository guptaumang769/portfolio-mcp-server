package com.umang.mcp.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.umang.mcp.tools.BookMyShowTools;
import com.umang.mcp.tools.UberTools;

/**
 * Wires our {@code @Tool} beans into the MCP server.
 *
 * <p>Spring AI's MCP server auto-configuration ({@code spring-ai-starter-mcp-server-webmvc}) picks up
 * every {@link ToolCallbackProvider} bean and advertises the tools it exposes over the Model Context
 * Protocol. We build one provider from our two tool objects using the Spring AI 1.0.0 API
 * {@code MethodToolCallbackProvider.builder().toolObjects(...).build()} — it reflects over each object,
 * turns every {@code @Tool}-annotated method into a {@code ToolCallback} (name, description, JSON schema),
 * and returns them via {@link ToolCallbackProvider#getToolCallbacks()}.</p>
 *
 * <p>All 10 tools (5 BookMyShow + 5 Uber) are registered here in one provider.</p>
 */
@Configuration
public class McpConfig {

    @Bean
    ToolCallbackProvider portfolioTools(BookMyShowTools bookMyShowTools, UberTools uberTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(bookMyShowTools, uberTools)
                .build();
    }
}
