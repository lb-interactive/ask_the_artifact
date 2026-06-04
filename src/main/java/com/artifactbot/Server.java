package com.artifactbot;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;

import io.javalin.Javalin;

/**
 * Web server for Ask the Artifact.
 *
 *   GET  /health  -> "ok"
 *   POST /chat     -> { "artifact": "...", "mode": "...", "message": "..." }
 *                     returns 200 { "reply": "..." }  on success
 *                     returns 400 { "error": "..." }  on bad input
 *                     returns 500 { "error": "..." }  on server failure
 */
public class Server {

    private static final String MODEL = "claude-haiku-4-5";

    // Only these exact artifact files may be loaded (prevents arbitrary file access).
    private static final Set<String> ALLOWED_ARTIFACTS = Set.of(
        "statue-of-david.md",
        "scanning-electron-microscope.md",
        "nike.md"
    );

    private static final Set<String> ALLOWED_MODES = Set.of("KIDS", "ADULT");

    public static void main(String[] args) {
        // Read and TRIM the API key (a stray newline in the env var breaks the HTTP header).
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null) {
            apiKey = apiKey.trim();
        }
        AnthropicClient client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .build();

        // Hosts provide the port via $PORT; fall back to 7070 locally.
        int port = 7070;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort.trim());
        }

        Javalin app = Javalin.create(config -> {
            // TEMPORARY: allow any origin to confirm CORS was the blocker and
            // get unblocked. Once the chat works, narrow this back to your
            // Lovable domain with .allowHost("https://...").
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        });

        app.get("/health", ctx -> ctx.result("ok"));

        app.post("/chat", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String artifact = body.get("artifact") == null ? "" : body.get("artifact").toString();
            String mode = body.get("mode") == null ? "" : body.get("mode").toString().toUpperCase();
            String message = body.get("message") == null ? "" : body.get("message").toString();

            // Optional conversation history: a list of {role, content} objects,
            // where role is "user" or "assistant" (the artifact). Sending this
            // lets the artifact follow the thread, so a bare "yes" continues
            // what it just offered instead of confusing it.
            Object historyObj = body.get("history");

            // Validate. Each failure sets the status AND returns immediately.
            if (!ALLOWED_ARTIFACTS.contains(artifact)) {
                ctx.status(400).json(Map.of("error", "Unknown artifact."));
                return;
            }
            if (!ALLOWED_MODES.contains(mode)) {
                ctx.status(400).json(Map.of("error", "Mode must be KIDS or ADULT."));
                return;
            }
            if (message == null || message.isBlank()) {
                ctx.status(400).json(Map.of("error", "Message is empty."));
                return;
            }

            try {
                String systemPrompt = App.buildSystemPrompt(artifact, mode);

                MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(Model.of(MODEL))
                    .maxTokens(400L)
                    .system(systemPrompt);

                // Replay prior turns (if any) in order, so the model has context.
                if (historyObj instanceof List<?> history) {
                    for (Object turnObj : history) {
                        if (turnObj instanceof Map<?, ?> turn) {
                            Object role = turn.get("role");
                            Object content = turn.get("content");
                            if (role == null || content == null) continue;
                            String c = content.toString();
                            if (c.isBlank()) continue;
                            if ("assistant".equalsIgnoreCase(role.toString())) {
                                builder.addAssistantMessage(c);
                            } else {
                                builder.addUserMessage(c);
                            }
                        }
                    }
                }

                // Finally, the visitor's newest message.
                builder.addUserMessage(message);

                Message response = client.messages().create(builder.build());
                String reply = response.content().get(0).text().get().text().trim();

                // SUCCESS: 200 with the reply. (ctx.json defaults to status 200.)
                ctx.json(Map.of("reply", reply));

            } catch (Exception e) {
                System.err.println("[/chat ERROR] " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
                ctx.status(500).json(Map.of("error", "The exhibit could not respond right now."));
            }
        });

        app.start(port);
        System.out.println("Ask the Artifact server running on port " + port);
    }
}