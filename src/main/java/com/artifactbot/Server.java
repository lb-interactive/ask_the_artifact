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
 *   POST /chat     -> { artifact, mode, message, history? } -> { reply }
 *
 * MULTILINGUAL (auto-detect): the artifact detects whatever language the
 * visitor writes in and replies in that same language, switching automatically
 * whenever the visitor switches. No language field needed -- it reads the
 * visitor's actual words. Sending conversation history makes the "stick to the
 * current language until they change it" behavior reliable.
 */
public class Server {

    private static final String MODEL = "claude-haiku-4-5";

    private static final Set<String> ALLOWED_ARTIFACTS = Set.of(
        "statue-of-david.md",
        "scanning-electron-microscope.md",
        "nike.md"
    );

    private static final Set<String> ALLOWED_MODES = Set.of("KIDS", "ADULT");

    public static void main(String[] args) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null) apiKey = apiKey.trim();
        final AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();

        int port = 7070;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) port = Integer.parseInt(envPort.trim());

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        });

        app.get("/health", ctx -> ctx.result("ok"));

        app.post("/chat", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String artifact = body.get("artifact") == null ? "" : body.get("artifact").toString();
            String mode = body.get("mode") == null ? "" : body.get("mode").toString().toUpperCase();
            String message = body.get("message") == null ? "" : body.get("message").toString();
            Object historyObj = body.get("history");

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

                // MULTILINGUAL (auto-detect): instruct the artifact to mirror the
                // visitor's language and switch whenever the visitor switches.
                systemPrompt = systemPrompt + "\n\n## LANGUAGE (auto-detect)\n"
                    + "Detect the language of the visitor's MOST RECENT message and reply ENTIRELY in that same language. "
                    + "If the visitor writes in Spanish, reply in Spanish; in Japanese, reply in Japanese; and so on. "
                    + "Keep replying in that language for every turn UNTIL the visitor switches to a different language, "
                    + "then switch with them starting from that message. "
                    + "Never announce or comment on the language change -- just speak naturally in their language. "
                    + "Keep your character, your assigned tone (KIDS or ADULT), and ALL other rules; only the language changes.";

                MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(Model.of(MODEL))
                    .maxTokens(400L)
                    .system(systemPrompt);

                // History lets the model see prior turns, so it knows what language
                // the conversation is currently in and only switches when the visitor does.
                if (historyObj instanceof List<?> history) {
                    for (Object turnObj : history) {
                        if (turnObj instanceof Map<?, ?> turn) {
                            Object role = turn.get("role");
                            Object content = turn.get("content");
                            if (role == null || content == null) continue;
                            String c = content.toString();
                            if (c.isBlank()) continue;
                            if ("assistant".equalsIgnoreCase(role.toString())) builder.addAssistantMessage(c);
                            else builder.addUserMessage(c);
                        }
                    }
                }
                builder.addUserMessage(message);

                Message response = client.messages().create(builder.build());
                String reply = response.content().get(0).text().get().text().trim();

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