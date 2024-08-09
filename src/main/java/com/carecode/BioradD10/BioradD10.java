package com.carecode.BioradD10;

import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Quantity;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.*;

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
    private static String fhirServerBaseUrl;
    private static String username;
    private static String password;
    static String departmentId;
    static String analyzerId;
    static String analyzerName;

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
            JSONObject limsSettings = middlewareSettings.getJSONObject("limsSettings");

            baseURL = analyzerDetails.getString("baseURL");
            queryFrequencyInMinutes = communicationSettings.getInt("queryFrequencyInMinutes");
            queryForYesterdayResults = communicationSettings.getBoolean("queryForYesterdayResults");
            fhirServerBaseUrl = limsSettings.getString("fhirServerBaseUrl");
            username = limsSettings.getString("username");
            password = limsSettings.getString("password");
            departmentId = analyzerDetails.getString("departmentId");
            analyzerName = analyzerDetails.getString("analyzerName");
            analyzerId = analyzerDetails.getString("analyzerId");

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

    public static void sendObservationsToLims(List<Map.Entry<String, String>> observations, Date date) {
        logger.info("Sending observations to LIMS");

        // Create a FHIR context
        FhirContext ctx = FhirContext.forR4();

        // Create a client
        IGenericClient client = ctx.newRestfulGenericClient(fhirServerBaseUrl);

        // Basic Authentication
        client.registerInterceptor(new BasicAuthInterceptor(username, password));

        // Determine the file name for the day's sample IDs
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String fileName = "processed_samples_" + dateFormat.format(date) + ".txt";
        Set<String> processedSamples = new HashSet<>();

        // Load the processed samples from the file if it exists
        try {
            Path path = Paths.get(fileName);
            if (Files.exists(path)) {
                processedSamples.addAll(Files.readAllLines(path));
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error reading processed samples file: " + fileName, e);
        }

        // Prepare to write to the file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
            for (Map.Entry<String, String> entry : observations) {
                String sampleId = entry.getKey();
                String hba1cValue = entry.getValue();

                // Check if the sample ID has already been processed
                if (!processedSamples.contains(sampleId)) {
                    // Create an Observation resource
                    Observation observation = new Observation();
                    observation.setStatus(Observation.ObservationStatus.FINAL);

// Sample ID Identifier
                    observation.addIdentifier(new Identifier()
                            .setSystem("http://carecode.org/fhir/identifiers/patient_sample_id")
                            .setValue(sampleId));

// Analyzer Name Identifier
                    observation.addIdentifier(new Identifier()
                            .setSystem("http://carecode.org/fhir/identifiers/analyzer_id")
                            .setValue(analyzerId));

                    // Analyzer ID Identifier
                    observation.addIdentifier(new Identifier()
                            .setSystem("http://carecode.org/fhir/identifiers/analyzer_name")
                            .setValue(analyzerName));

// Department Identifier
                    observation.addIdentifier(new Identifier()
                            .setSystem("http://carecode.org/fhir/identifiers/department_id")
                            .setValue(departmentId));

// Observation Code (HbA1c)
                    observation.getCode().addCoding()
                            .setSystem("http://loinc.org")
                            .setCode("4548-4")
                            .setDisplay("Hemoglobin A1c/Hemoglobin.total in Blood");

// HbA1c Value
                    observation.setValue(new Quantity()
                            .setValue(Double.parseDouble(hba1cValue))
                            .setUnit("%")
                            .setSystem("http://unitsofmeasure.org")
                            .setCode("%"));

// Issued Date
                    observation.setIssued(new Date());

                    // Send the Observation to the FHIR server
                    MethodOutcome outcome = client.create().resource(observation).execute();

                    // Log the outcome
                    logger.info("Observation created with ID: " + outcome.getId().getValue());

                    // Write the sample ID to the file
                    writer.write(sampleId);
                    writer.newLine();

                    // Add the sample ID to the processed set
                    processedSamples.add(sampleId);
                } else {
                    logger.info("Sample ID " + sampleId + " has already been processed for today.");
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing to processed samples file: " + fileName, e);
        }
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
                if (!todayData.isEmpty()) {
                    sendObservationsToLims(todayData, new Date());
                }
            }

            if (queryForYesterdayResults) {
                LocalDate yesterday = today.minusDays(1);
                Date yday = Date.from(yesterday.atStartOfDay(ZoneId.systemDefault()).toInstant());
                String yesterdayUrl = generateUrlForDate(yesterday);
                if (yesterdayUrl != null) {
                    String htmlContent = fetchHtmlContent(yesterdayUrl);
                    logger.info("HTML Content for yesterday: " + htmlContent);
                    List<Map.Entry<String, String>> yesterdayData = extractSampleData(htmlContent);
                    if (!yesterdayData.isEmpty()) {
                        sendObservationsToLims(yesterdayData, yday);
                    }
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
