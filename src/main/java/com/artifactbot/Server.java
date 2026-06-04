package com.artifactbot;

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
            // CORS: allow only your Lovable frontend(s).
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> it.allowHost("https://artifact-whispers-interactive.lovable.app"));
                cors.addRule(it -> it.allowHost("https://artifact-whispers-interactive.lovableproject.com"));
                cors.addRule(it -> it.allowHost("https://lovable.dev"));
            });
        });

        app.get("/health", ctx -> ctx.result("ok"));

        app.post("/chat", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, String> body = ctx.bodyAsClass(Map.class);

            String artifact = body.getOrDefault("artifact", "");
            String mode = body.getOrDefault("mode", "").toUpperCase();
            String message = body.getOrDefault("message", "");

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

                MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.of(MODEL))
                    .maxTokens(400L)
                    .system(systemPrompt)
                    .addUserMessage(message)
                    .build();

                Message response = client.messages().create(params);
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
}// rebuild 1780602197
