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
 * Exposes:
 *   GET  /health        -> simple "ok" so hosts/Lovable can check it's alive
 *   POST /chat          -> { "artifact": "...", "mode": "...", "message": "..." }
 *                          returns { "reply": "..." }
 *
 * Reuses App.buildSystemPrompt() so the web bot and the terminal bot share
 * the exact same prompt logic and config files.
 *
 * Run locally:   mvn compile exec:java
 * Build a jar:   mvn clean package   then   java -jar target/artifact-ai-1.0-SNAPSHOT.jar
 */
public class Server {

    private static final String MODEL = "claude-haiku-4-5";

    // SECURITY: only these exact artifact files may be loaded. Visitor/website
    // input is checked against this allow-list, so untrusted input can never
    // be used to read an arbitrary file path off the server.
    private static final Set<String> ALLOWED_ARTIFACTS = Set.of(
        "statue-of-david.md",
        "scanning-electron-microscope.md",
        "nike.md"
    );

    private static final Set<String> ALLOWED_MODES = Set.of("KIDS", "ADULT");

    public static void main(String[] args) {
        // Read the API key explicitly and TRIM it. Pasting a key into a host's
        // env-var field often leaves a trailing newline/space, which is an
        // illegal HTTP header character and crashes the request. Trimming makes
        // the server robust to that. (fromEnv() would NOT trim.)
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null) {
            apiKey = apiKey.trim();
        }
        AnthropicClient client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .build();

        // Hosts (Railway/Render/Fly) tell your app which port to use via $PORT.
        // Fall back to 7070 when running locally.
        int port = 7070;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort.trim());
        }

        Javalin app = Javalin.create(config -> {
            // CORS: allow only YOUR Lovable frontend(s) to call this server.
            config.bundledPlugins.enableCors(cors -> {
                // Published app:
                cors.addRule(it -> it.allowHost("https://artifact-whispers-interactive.lovable.app"));
                // Lovable in-editor preview (the editor runs on lovable.dev/lovableproject.com).
                // These let the chat work while you test inside the Lovable editor.
                cors.addRule(it -> it.allowHost("https://lovable.dev"));
                cors.addRule(it -> it.allowHost("https://artifact-whispers-interactive.lovableproject.com"));
            });
        });

        // Health check
        app.get("/health", ctx -> ctx.result("ok"));

        // Main chat endpoint
        app.post("/chat", ctx -> {
            // Parse the JSON body into a simple map.
            @SuppressWarnings("unchecked")
            Map<String, String> body = ctx.bodyAsClass(Map.class);

            String artifact = body.getOrDefault("artifact", "");
            String mode = body.getOrDefault("mode", "").toUpperCase();
            String message = body.getOrDefault("message", "");

            // Validate inputs against the allow-lists.
            if (!ALLOWED_ARTIFACTS.contains(artifact)) {
                ctx.status(400).json(Map.of("error", "Unknown artifact."));
                return;
            }
            if (!ALLOWED_MODES.contains(mode)) {
                ctx.status(400).json(Map.of("error", "Mode must be KIDS or ADULT."));
                return;
            }
            if (message.isBlank()) {
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

                ctx.json(Map.of("reply", reply));

            } catch (Exception e) {
                // Print the real cause to the server logs so failures are diagnosable.
                // (The visitor still only sees the friendly message.)
                System.err.println("[/chat ERROR] " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
                ctx.status(500).json(Map.of("error", "The exhibit could not respond right now."));
            }
        });

        app.start(port);
        System.out.println("Ask the Artifact server running on port " + port);
    }
}