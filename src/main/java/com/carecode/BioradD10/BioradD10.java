package com.carecode.BioradD10;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.*;
import org.json.JSONObject;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;

public class BioradD10 {

    private static final Logger logger = Logger.getLogger(BioradD10.class.getName());

    static {
        // Configure logger to show all levels
        LogManager.getLogManager().reset();
        logger.setLevel(Level.ALL);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.ALL);
        logger.addHandler(ch);
    }

    private static String baseURL;
    private static int queryFrequencyInMinutes;
    private static boolean queryForYesterdayResults;

    public BioradD10() {
    }

    public String readResult() throws IOException {
        logger.info("readResult method called");
        return null;
    }

    public static void loadConfig(String configFilePath) {
        logger.info("Loading configuration from file: " + configFilePath);

        try {
            String content = new String(Files.readAllBytes(Paths.get(configFilePath)));
            JSONObject config = new JSONObject(content);
            JSONObject middlewareSettings = config.getJSONObject("middlewareSettings");
            JSONObject analyzerDetails = middlewareSettings.getJSONObject("analyzerDetails");
            JSONObject communicationSettings = middlewareSettings.getJSONObject("communication");

            baseURL = analyzerDetails.getString("baseURL");
            queryFrequencyInMinutes = communicationSettings.getInt("queryFrequencyInMinutes");
            queryForYesterdayResults = communicationSettings.getBoolean("queryForYesterdayResults");

            logger.info("Configuration loaded successfully");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while loading configuration", e);
        }
    }

    public static String generateUrlForDate(LocalDate date) {
        logger.info("Generating URL for date: " + date);

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            String dateStr = date.format(formatter);

            String url = baseURL + "?page=result&test=HBA1C&StartDate=" + encodeDate(dateStr) + "&EndDate=" + encodeDate(dateStr);
            logger.info("Generated URL: " + url);
            return url;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while generating URL", e);
            return null;
        }
    }

    private static String encodeDate(String date) {
        return date.replace("/", "%2F");
    }

    public static String fetchHtmlContent(String urlString) throws IOException {
        logger.info("Fetching HTML content from URL: " + urlString);

        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            logger.fine("Sending GET request to URL: " + urlString);
            int responseCode = connection.getResponseCode();
            logger.info("Response Code: " + responseCode);

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuilder htmlContent = new StringBuilder();

            while ((inputLine = in.readLine()) != null) {
                htmlContent.append(inputLine);
            }

            in.close();
            logger.info("Fetched HTML content successfully");
            return htmlContent.toString();

        } catch (IOException e) {
            logger.log(Level.SEVERE, "IOException occurred while fetching HTML content", e);
            throw e;
        }
    }

    public static List<Map.Entry<String, String>> extractSampleData(String htmlContent) {
        logger.info("Extracting Sample ID and HbA1c percentage from HTML content");

        List<Map.Entry<String, String>> sampleData = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(htmlContent);
            Elements rows = doc.select("table tr");

            for (Element row : rows) {
                Elements cells = row.select("td");

                if (cells.size() > 5) {
                    String sampleId = cells.get(3).text();
                    String hba1c = cells.get(5).text();
                    sampleData.add(new AbstractMap.SimpleEntry<>(sampleId, hba1c));
                }
            }

            logger.info("Sample data extraction successful");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while extracting sample data", e);
        }

        return sampleData;
    }

    public static void sendRequests() {
        logger.info("Sending requests for today's and potentially yesterday's results");

        try {
            LocalDate today = LocalDate.now();
            String todayUrl = generateUrlForDate(today);
            if (todayUrl != null) {
                String htmlContent = fetchHtmlContent(todayUrl);
                logger.info("HTML Content for today: " + htmlContent);
                List<Map.Entry<String, String>> todayData = extractSampleData(htmlContent);
                logger.info("Today's data: " + todayData);
            }

            if (queryForYesterdayResults) {
                LocalDate yesterday = today.minusDays(1);
                String yesterdayUrl = generateUrlForDate(yesterday);
                if (yesterdayUrl != null) {
                    String htmlContent = fetchHtmlContent(yesterdayUrl);
                    logger.info("HTML Content for yesterday: " + htmlContent);
                    List<Map.Entry<String, String>> yesterdayData = extractSampleData(htmlContent);
                    logger.info("Yesterday's data: " + yesterdayData);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Exception occurred while sending requests", e);
        }
    }

    public static void main(String[] args) {
        logger.info("Main method started");

        try {
            String configPath = "config.json"; // Directly referencing the config file in the project root
            loadConfig(configPath);
            sendRequests(); // Initial request at the start

            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    sendRequests();
                }
            }, queryFrequencyInMinutes * 60 * 1000, queryFrequencyInMinutes * 60 * 1000); // Schedule at specified interval

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred in main method", e);
        }

        logger.info("Main method ended");
    }
}
