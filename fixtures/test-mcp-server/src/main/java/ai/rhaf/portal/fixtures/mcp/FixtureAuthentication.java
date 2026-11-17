package ai.rhaf.portal.fixtures.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.vertx.ext.web.Router;
import com.fasterxml.jackson.databind.JsonNode;

/** Optional bearer authentication for MCP and control requests. */
@ApplicationScoped
public class FixtureAuthentication {

    @ConfigProperty(name = "fixture.auth-token")
    Optional<String> token;

    void install(@Observes Router router) {
        router.route().order(Integer.MIN_VALUE).handler(context -> {
            String path = context.normalizedPath();
            boolean protectedPath = path.equals("/mcp")
                    || path.startsWith("/mcp/")
                    || path.equals("/control")
                    || path.startsWith("/control/");
            if (protectedPath && token.isPresent()) {
                String supplied = context.request().getHeader("Authorization");
                byte[] expected = ("Bearer " + token.orElseThrow()).getBytes(StandardCharsets.UTF_8);
                if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                    context.response()
                            .setStatusCode(401)
                            .putHeader("WWW-Authenticate", "Bearer")
                            .end();
                    return;
                }
            }
            context.next();
        });
    }

    boolean containsToken(JsonNode input) {
        return token.filter(credential -> containsCredential(input, credential)).isPresent();
    }

    private static boolean containsCredential(JsonNode input, String credential) {
        if (input.isTextual() && input.textValue().contains(credential)) {
            return true;
        }
        Iterator<String> fieldNames = input.fieldNames();
        while (fieldNames.hasNext()) {
            if (fieldNames.next().contains(credential)) {
                return true;
            }
        }
        for (JsonNode child : input) {
            if (containsCredential(child, credential)) {
                return true;
            }
        }
        return false;
    }
}
