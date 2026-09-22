package org.jobrunr.dashboard.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jobrunr.dashboard.JobRunrDashboardWebServer;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.FreePortFinder;
import org.jobrunr.utils.mapper.JsonMapper;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jobrunr.dashboard.JobRunrDashboardWebServerConfiguration.usingStandardDashboardConfiguration;
import static org.jobrunr.jobs.JobTestBuilder.aFailedJob;

class McpHubConnectorTest {

    private StorageProvider storageProvider;
    private JobRunrDashboardWebServer dashboardWebServer;
    private FakeHub hub;

    @BeforeEach
    void setUp() throws IOException {
        storageProvider = new InMemoryStorageProvider();
        storageProvider.setJobMapper(new JobMapper(new JacksonJsonMapper()));
        hub = new FakeHub();
    }

    @AfterEach
    void tearDown() {
        if (dashboardWebServer != null) dashboardWebServer.stop();
        hub.stop();
        storageProvider.close();
    }

    @Test
    void onlyDashboardApiRoutesAreReachableAndMutatingRoutesNeedAnOperateToken() {
        assertThat(McpHubConnector.isAllowed("GET", "/api/jobs?state=FAILED&offset=0&limit=20", false)).isTrue();
        assertThat(McpHubConnector.isAllowed("GET", "/api/jobs/0f5e0e3c-0a5c-4a5c-9c3b-0e7d3b5a1c2d", false)).isTrue();
        assertThat(McpHubConnector.isAllowed("GET", "/dashboard/index.html", false)).isFalse();
        assertThat(McpHubConnector.isAllowed("GET", "/sse/jobstats", false)).isFalse();
        assertThat(McpHubConnector.isAllowed("GET", "/api/../dashboard", false)).isFalse();
        assertThat(McpHubConnector.isAllowed("GET", "/api/jobs @evil.com", false)).isFalse();

        assertThat(McpHubConnector.isAllowed("POST", "/api/jobs/0f5e0e3c-0a5c-4a5c-9c3b-0e7d3b5a1c2d/requeue", false)).isFalse();
        assertThat(McpHubConnector.isAllowed("POST", "/api/jobs/0f5e0e3c-0a5c-4a5c-9c3b-0e7d3b5a1c2d/requeue", true)).isTrue();
        assertThat(McpHubConnector.isAllowed("POST", "/api/recurring-jobs/daily-report/trigger", true)).isTrue();
        assertThat(McpHubConnector.isAllowed("POST", "/api/recurring-jobs/org.acme.ReportService.run(),daily/trigger", true)).isTrue();
        assertThat(McpHubConnector.isAllowed("DELETE", "/api/jobs/0f5e0e3c-0a5c-4a5c-9c3b-0e7d3b5a1c2d", true)).isTrue();
        assertThat(McpHubConnector.isAllowed("DELETE", "/api/problems/cpu-allocation-irregularity", true)).isTrue();

        assertThat(McpHubConnector.isAllowed("DELETE", "/api/recurring-jobs/daily-report", true)).isFalse();
        assertThat(McpHubConnector.isAllowed("PUT", "/api/jobs/0f5e0e3c-0a5c-4a5c-9c3b-0e7d3b5a1c2d", true)).isFalse();
        assertThat(McpHubConnector.isAllowed("POST", "/api/jobs", true)).isFalse();
    }

    @Test
    void relaysReadRequestsWithRedactedJobParameters() {
        Job failedJob = storageProvider.save(aFailedJob().build());
        startDashboardConnectedToHub("jrc_read_secret", true);

        hub.send("r1 GET /api/jobs/" + failedJob.getId());

        FakeHub.Result result = hub.awaitResult("r1");
        assertThat(result.status).isEqualTo(200);
        assertThat(result.body)
                .contains(failedJob.getId().toString())
                .contains("\"object\":\"<redacted>\"")
                .doesNotContain("a test");
        assertThat(hub.lastPollHeaders.get("Authorization")).isEqualTo("Bearer jrc_read_secret");
        assertThat(hub.lastPollHeaders.get("X-jobrunr-redaction")).isEqualTo("true");
        assertThat(hub.lastPollHeaders.get("X-jobrunr-framework")).isEqualTo("plain-java");
    }

    @Test
    void sendsJobParameterValuesWhenRedactionIsDisabled() {
        Job failedJob = storageProvider.save(aFailedJob().build());
        startDashboardConnectedToHub("jrc_read_secret", false);

        hub.send("r1 GET /api/jobs/" + failedJob.getId());

        assertThat(hub.awaitResult("r1").body).contains("a test");
    }

    @Test
    void refusesMutatingRequestsForAReadToken() {
        Job failedJob = storageProvider.save(aFailedJob().build());
        startDashboardConnectedToHub("jrc_read_secret", true);

        hub.send("r1 POST /api/jobs/" + failedJob.getId() + "/requeue");

        assertThat(hub.awaitResult("r1").status).isEqualTo(403);
        assertThat(storageProvider.getJobById(failedJob.getId())).satisfies(job -> assertThat(job.getState()).isEqualTo(StateName.FAILED));
    }

    @Test
    void executesMutatingRequestsForAnOperateToken() {
        Job failedJob = storageProvider.save(aFailedJob().build());
        startDashboardConnectedToHub("jrc_operate_secret", true);

        hub.send("r1 POST /api/jobs/" + failedJob.getId() + "/requeue");

        assertThat(hub.awaitResult("r1").status).isEqualTo(204);
        assertThat(storageProvider.getJobById(failedJob.getId())).satisfies(job -> assertThat(job.getState()).isEqualTo(StateName.ENQUEUED));
    }

    @Test
    void usesTheDashboardCredentialsForTheLocalCall() {
        startDashboardConnectedToHub("jrc_read_secret", true, "admin", "s3cret");

        hub.send("r1 GET /api/servers");

        assertThat(hub.awaitResult("r1").status).isEqualTo(200);
    }

    private void startDashboardConnectedToHub(String token, boolean redact) {
        startDashboardConnectedToHub(token, redact, null, null);
    }

    private void startDashboardConnectedToHub(String token, boolean redact, String username, String password) {
        JsonMapper jsonMapper = new JacksonJsonMapper();
        dashboardWebServer = new JobRunrDashboardWebServer(storageProvider, jsonMapper, usingStandardDashboardConfiguration()
                .andPort(FreePortFinder.nextFreePort(8000))
                .andBasicAuthentication(username, password)
                .andMcpConnector(token, hub.url())
                .andMcpJobParameterRedaction(redact));
        dashboardWebServer.start();
    }

    private static class FakeHub {
        private final HttpServer server;
        private final BlockingQueue<String> pending = new LinkedBlockingQueue<>();
        private final Map<String, Result> results = new ConcurrentHashMap<>();
        private volatile Map<String, String> lastPollHeaders = new ConcurrentHashMap<>();

        FakeHub() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/connector/poll", this::poll);
            server.createContext("/connector/results/", this::result);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void send(String requestLine) {
            pending.add(requestLine);
        }

        Result awaitResult(String requestId) {
            await().atMost(ofSeconds(10)).until(() -> results.containsKey(requestId));
            return results.get(requestId);
        }

        void stop() {
            server.stop(0);
        }

        private void poll(HttpExchange exchange) throws IOException {
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
            lastPollHeaders = headers;
            try {
                String request = pending.poll(200, TimeUnit.MILLISECONDS);
                if (request == null) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    byte[] body = (request + "\n").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        private void result(HttpExchange exchange) throws IOException {
            String requestId = exchange.getRequestURI().getPath().substring("/connector/results/".length());
            try (InputStream in = exchange.getRequestBody()) {
                byte[] body = in.readAllBytes();
                results.put(requestId, new Result(Integer.parseInt(exchange.getRequestHeaders().getFirst("X-JobRunr-Status")), new String(body, StandardCharsets.UTF_8)));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }

        static class Result {
            final int status;
            final String body;

            Result(int status, String body) {
                this.status = status;
                this.body = body;
            }
        }
    }
}
