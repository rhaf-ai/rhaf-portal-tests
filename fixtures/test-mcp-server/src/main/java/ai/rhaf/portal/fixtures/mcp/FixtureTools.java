package ai.rhaf.portal.fixtures.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.quarkus.runtime.StartupEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Real HTTP MCP tools backed by the controlled fixture state. */
@ApplicationScoped
public class FixtureTools implements ToolFilter {
    @Inject
    ToolManager tools;

    @Inject
    FixtureState state;

    @Inject
    ObjectMapper mapper;

    void register(@Observes StartupEvent event) {
        registerTool("read_state", "Read synthetic state for an exact target", false);
        registerTool("set_state", "Set synthetic state for an exact target", true);
    }

    private void registerTool(String name, String description, boolean write) {
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var properties = schema.putObject("properties");
        properties.putObject("target").put("type", "string").put("minLength", 1).put("maxLength", 128);
        var required = schema.putArray("required").add("target");
        if (write) {
            properties
                    .putObject("value")
                    .put("type", "integer")
                    .put("minimum", Integer.MIN_VALUE)
                    .put("maximum", Integer.MAX_VALUE);
            properties.putObject("enabled").put("type", "boolean");
            properties.putObject("metadata").put("type", "object");
            required.add("value").add("enabled");
        }
        tools.newTool(name)
                .setDescription(description)
                .setInputSchema(schema)
                .setAsyncHandler(arguments -> {
                    JsonNode request = mapper.valueToTree(
                            arguments.rawMessage().asJsonObject().getMap());
                    return state.invoke(name, request.path("params").path("arguments"));
                })
                .register();
    }

    /** Applies the current catalog to both discovery and invocation. */
    @Override
    public boolean test(ToolInfo tool, FilterContext context) {
        return state.enabled(tool.name());
    }
}
