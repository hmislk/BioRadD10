package com.carecode.BioradD10;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonElement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class LisCommunicator {

    private static final String CONFIG_FILE_PATH = "config.json";
    private JsonObject config;

    public LisCommunicator() {
        loadConfig();
    }

    private void loadConfig() {
        try {
            String configContent = new String(Files.readAllBytes(Paths.get(CONFIG_FILE_PATH)), StandardCharsets.UTF_8);
            config = JsonParser.parseString(configContent).getAsJsonObject();
        } catch (IOException | JsonSyntaxException e) {
            e.printStackTrace();
        }
    }

    public void sendTestResultsToLIS(String rawMessage) {
        JsonObject analyzerDetails = config.getAsJsonObject("middlewareSettings").getAsJsonObject("analyzerDetails");
        JsonObject testResults = new JsonObject();
        testResults.addProperty("analyzerName", analyzerDetails.get("analyzerName").getAsString());
        testResults.addProperty("serialNumber", analyzerDetails.get("serialNumber").getAsString());
        testResults.addProperty("analyzerId", analyzerDetails.get("analyzerId").getAsString());
        testResults.addProperty("message", rawMessage);

        try {
            String urlString = config.getAsJsonObject("middlewareSettings")
                    .getAsJsonObject("limsSettings")
                    .get("pushResultsEndpoint").getAsString();
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = testResults.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("Test results sent successfully.");
            } else {
                System.out.println("Failed to send test results.");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
