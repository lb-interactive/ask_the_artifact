package com.artifactbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;

public class App {

    // Where the prompt files live, relative to where you run the app.
    private static final Path CONFIG_DIR = Path.of("artifact-config");
    private static final Path OBJECTS_DIR = CONFIG_DIR.resolve("objects");

    /**
     * Loads the reusable scaffolding prompt and injects the chosen object
     * config + tone mode into the {{OBJECT_CONFIG}} and {{MODE}} placeholders.
     * This is the whole "swappable config" idea: the scaffolding never changes,
     * only which object file we read.
     */
    public static String buildSystemPrompt(String objectFileName, String mode) throws IOException {
        String scaffolding = Files.readString(CONFIG_DIR.resolve("scaffolding.md"));
        String objectConfig = Files.readString(OBJECTS_DIR.resolve(objectFileName));

        return scaffolding
            .replace("{{MODE}}", mode)
            .replace("{{OBJECT_CONFIG}}", objectConfig);
    }

    /**
     * Returns true if the visitor's input signals they want to leave.
     * Matches a few common goodbye words so the visitor isn't forced to
     * type one exact phrase.
     */
    public static boolean isGoodbye(String input) {
        String t = input.trim().toLowerCase();
        return t.equals("bye")
            || t.equals("goodbye")
            || t.equals("no")
            || t.equals("exit")
            || t.equals("quit");
    }

    public static void main(String[] args) throws IOException {

        // fromEnv() reads the API key from the ANTHROPIC_API_KEY environment variable.
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        Scanner scanner = new Scanner(System.in);

        // ---- Pick which object to BE (swappable config) ----
        System.out.println("Which artifact should I become?");
        System.out.println("  1) Statue of David   (historic)");
        System.out.println("  2) Scanning Electron Microscope   (scientific)");
        System.out.println("  3) Nike   (branded)");
        System.out.print("Choose 1-3: ");
        String objectChoice = scanner.nextLine().trim();

        String objectFile;
        switch (objectChoice) {
            case "2": objectFile = "scanning-electron-microscope.md"; break;
            case "3": objectFile = "nike.md"; break;
            default:  objectFile = "statue-of-david.md"; break;
        }

        // ---- Pick the tone mode (tone switching) ----
        System.out.print("Audience? Type 'kids' or 'adult': ");
        String modeInput = scanner.nextLine().trim().toLowerCase();
        String mode = modeInput.equals("adult") ? "ADULT" : "KIDS";

        // ---- Build the merged system prompt ----
        String systemInstruction = buildSystemPrompt(objectFile, mode);

        System.out.println("\nThe exhibit is now live. Ask me anything about myself");
        System.out.println("(or say 'bye' if you have no other questions).\n");

        while (true) {
            System.out.print("Visitor: ");
            String visitorQuestion = scanner.nextLine().trim();

            // If the visitor signals they're done, ask the object for ONE final
            // in-character remark, print it, then stop the program.
            if (isGoodbye(visitorQuestion)) {
                MessageCreateParams farewellParams = MessageCreateParams.builder()
                    .model(Model.of("claude-haiku-4-5"))
                    .maxTokens(150L)
                    .system(systemInstruction)
                    // The visitor's actual words are passed so the object can react
                    // to them, plus an instruction to give a short goodbye and stop.
                    .addUserMessage("The visitor is leaving. They said: \"" + visitorQuestion
                        + "\". Give a short, warm farewell in character (1-2 sentences). "
                        + "Do not ask any more questions.")
                    .build();

                Message farewell = client.messages().create(farewellParams);
                System.out.println("Artifact: " + farewell.content().get(0).text().get().text() + "\n");
                break;
            }

            MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of("claude-haiku-4-5"))
                .maxTokens(300L)
                .system(systemInstruction)
                .addUserMessage(visitorQuestion)
                .build();

            Message response = client.messages().create(params);
            System.out.println("Artifact: " + response.content().get(0).text().get().text() + "\n");
        }

        scanner.close();
    }
}