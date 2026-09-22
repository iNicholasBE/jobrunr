package org.jobrunr.dashboard.mcp;

import org.jobrunr.configuration.JobRunr;
import org.jobrunr.utils.JarUtils;
import org.jobrunr.utils.StringUtils;
import org.jobrunr.utils.annotations.VisibleFor;
import org.jobrunr.utils.io.IOUtils;
import org.jobrunr.utils.threadpool.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.jobrunr.utils.StringUtils.isNotNullOrEmpty;
import static org.jobrunr.utils.StringUtils.substringBefore;
import static org.jobrunr.utils.reflection.ReflectionUtils.classExists;

/**
 * Connects the dashboard to the hosted JobRunr MCP server so AI agents can inspect and operate this cluster. The connection is
 * outbound only: the connector long-polls the hub for dashboard API requests, executes them against the local dashboard and posts
 * the result back. The hub holds the MCP logic; the connector only enforces the allowed routes, the token scope and the redaction
 * of job parameter values.
 */
public class McpHubConnector {

    public static final String DEFAULT_HUB_URL = "https://mcp.jobrunr.io";

    private static final Logger LOGGER = LoggerFactory.getLogger(McpHubConnector.class);
    private static final String OPERATE_TOKEN_PREFIX = "jrc_operate_";
    private static final Pattern API_PATH = Pattern.compile("/api/[A-Za-z0-9/_.%:,=&?()-]*");
    private static final Pattern REQUEUE_JOB = Pattern.compile("/api/jobs/[A-Za-z0-9-]+/requeue");
    private static final Pattern DELETE_JOB = Pattern.compile("/api/jobs/[A-Za-z0-9-]+");
    private static final Pattern TRIGGER_RECURRING_JOB = Pattern.compile("/api/recurring-jobs/[^/?]+/trigger");
    private static final Pattern DISMISS_PROBLEM = Pattern.compile("/api/problems/[^/?]+");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int POLL_TIMEOUT_MS = 60_000;
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final long REJECTED_BACKOFF_MS = 5 * 60_000;

    private final String token;
    private final String hubUrl;
    private final boolean redactJobParameters;
    private final String dashboardUrl;
    private final String dashboardAuthorization;
    private final String nodeId = UUID.randomUUID().toString();
    private final String version = JarUtils.getVersion(JobRunr.class);
    private final ExecutorService executorService = Executors.newFixedThreadPool(3, new NamedThreadFactory("jobrunr-mcp-connector", true));
    private volatile boolean connected;
    private volatile HttpURLConnection pollConnection;

    public McpHubConnector(String token, String hubUrl, boolean redactJobParameters, int dashboardPort, String dashboardUsername, String dashboardPassword) {
        this.token = token;
        this.hubUrl = isNotNullOrEmpty(hubUrl) ? hubUrl.replaceAll("/+$", "") : DEFAULT_HUB_URL;
        this.redactJobParameters = redactJobParameters;
        this.dashboardUrl = "http://127.0.0.1:" + dashboardPort;
        this.dashboardAuthorization = isNotNullOrEmpty(dashboardUsername) && isNotNullOrEmpty(dashboardPassword)
                ? "Basic " + Base64.getEncoder().encodeToString((dashboardUsername + ":" + dashboardPassword).getBytes(UTF_8))
                : null;
    }

    public void start() {
        executorService.execute(this::pollLoop);
        LOGGER.info("JobRunr MCP connector started with {} access and job parameter values {}, connecting to {}",
                isOperateToken() ? "read and operate" : "read-only", redactJobParameters ? "redacted" : "included", hubUrl);
    }

    public void stop() {
        executorService.shutdownNow();
        HttpURLConnection connection = pollConnection;
        if (connection != null) connection.disconnect();
    }

    private void pollLoop() {
        long backoffMs = 0;
        while (!executorService.isShutdown()) {
            try {
                Thread.sleep(backoffMs);
                for (String request : poll()) {
                    executorService.execute(() -> answer(request));
                }
                if (!connected) LOGGER.info("JobRunr MCP connector is connected to {}", hubUrl);
                connected = true;
                backoffMs = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (HubRejectedConnectorException e) {
                LOGGER.warn("JobRunr MCP hub refused the connector (HTTP {}), retrying in 5 minutes. {}", e.status, e.getMessage());
                connected = false;
                backoffMs = REJECTED_BACKOFF_MS;
            } catch (IOException e) {
                if (executorService.isShutdown()) return;
                if (connected) LOGGER.warn("JobRunr MCP connector lost its connection to {}, reconnecting: {}", hubUrl, e.getMessage());
                connected = false;
                backoffMs = Math.min(Math.max(1_000, backoffMs * 2), MAX_BACKOFF_MS);
            }
        }
    }

    private List<String> poll() throws IOException {
        HttpURLConnection connection = openHubConnection("GET", "/connector/poll", POLL_TIMEOUT_MS);
        connection.setRequestProperty("X-JobRunr-Version", version);
        connection.setRequestProperty("X-JobRunr-Java-Version", System.getProperty("java.version"));
        connection.setRequestProperty("X-JobRunr-Framework", detectFramework());
        connection.setRequestProperty("X-JobRunr-Redaction", String.valueOf(redactJobParameters));
        pollConnection = connection;
        try {
            int status = connection.getResponseCode();
            if (status == 401 || status == 403 || status == 409) throw new HubRejectedConnectorException(status, read(connection.getErrorStream()));
            if (status != 200 && status != 204) throw new IOException("Unexpected HTTP status " + status);
            return Stream.of(read(connection.getInputStream()).split("\n", -1)).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(toList());
        } finally {
            pollConnection = null;
            connection.disconnect();
        }
    }

    private void answer(String request) {
        String[] parts = request.split(" ", -1);
        if (parts.length != 3) return;
        String requestId = parts[0];
        String method = parts[1];
        String path = parts[2];
        try {
            postResult(requestId, isAllowed(method, path, isOperateToken())
                    ? callDashboard(method, path)
                    : DashboardResponse.error(403, "This connector token does not allow " + method + " " + path));
        } catch (IOException e) {
            LOGGER.debug("JobRunr MCP connector could not answer request {}", requestId, e);
        }
    }

    @VisibleFor("testing")
    static boolean isAllowed(String method, String path, boolean operate) {
        if (!API_PATH.matcher(path).matches() || path.contains("..")) return false;
        if ("GET".equals(method)) return true;
        if (!operate) return false;
        String route = substringBefore(path, "?");
        if ("POST".equals(method)) return REQUEUE_JOB.matcher(route).matches() || TRIGGER_RECURRING_JOB.matcher(route).matches();
        if ("DELETE".equals(method)) return DELETE_JOB.matcher(route).matches() || DISMISS_PROBLEM.matcher(route).matches();
        return false;
    }

    private DashboardResponse callDashboard(String method, String path) throws IOException {
        HttpURLConnection connection = openConnection(dashboardUrl + path, method, READ_TIMEOUT_MS);
        try {
            if (dashboardAuthorization != null) connection.setRequestProperty("Authorization", dashboardAuthorization);
            int status = connection.getResponseCode();
            if (status >= 500) {
                // why: the dashboard answers with a stack trace of which only the first line is useful to an agent
                return DashboardResponse.error(status, substringBefore(read(connection.getErrorStream()), "\n").trim());
            }
            String body = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (!redactJobParameters) return new DashboardResponse(status, body);
            String redacted = JobParameterRedactor.redact(body);
            return redacted != null ? new DashboardResponse(status, redacted) : DashboardResponse.error(500, "Response withheld as it could not be redacted");
        } finally {
            connection.disconnect();
        }
    }

    private void postResult(String requestId, DashboardResponse response) throws IOException {
        HttpURLConnection connection = openHubConnection("POST", "/connector/results/" + requestId, READ_TIMEOUT_MS);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-JobRunr-Status", String.valueOf(response.status));
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(response.body.getBytes(UTF_8));
            }
            connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openHubConnection(String method, String path, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = openConnection(hubUrl + path, method, readTimeoutMs);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("User-Agent", "JobRunr MCP connector " + version);
        connection.setRequestProperty("X-JobRunr-Node", nodeId);
        return connection;
    }

    private static HttpURLConnection openConnection(String url, String method, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        return connection;
    }

    private static String read(InputStream inputStream) throws IOException {
        if (inputStream == null) return "";
        StringWriter writer = new StringWriter();
        IOUtils.copyStream(inputStream, writer);
        return writer.toString();
    }

    private boolean isOperateToken() {
        return token.startsWith(OPERATE_TOKEN_PREFIX);
    }

    private static String detectFramework() {
        if (classExists("org.springframework.boot.SpringApplication")) return "spring-boot";
        if (classExists("io.quarkus.runtime.Quarkus")) return "quarkus";
        if (classExists("io.micronaut.runtime.Micronaut")) return "micronaut";
        return "plain-java";
    }

    private static class DashboardResponse {
        private final int status;
        private final String body;

        private DashboardResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }

        private static DashboardResponse error(int status, String message) {
            return new DashboardResponse(status, "{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        }
    }

    private static class HubRejectedConnectorException extends IOException {
        private final int status;

        private HubRejectedConnectorException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
