package org.jobrunr.dashboard;

import org.jobrunr.dashboard.mcp.McpHubConnector;

/**
 * This class allows to configure the JobRunrDashboard
 */
public class JobRunrDashboardWebServerConfiguration {
    int port = 8000;
    String username = null;
    String password = null;
    boolean allowAnonymousDataUsage = true;
    String mcpConnectorToken = null;
    String mcpHubUrl = McpHubConnector.DEFAULT_HUB_URL;
    boolean mcpRedactJobParameters = true;

    private JobRunrDashboardWebServerConfiguration() {

    }

    /**
     * This returns the default configuration with the JobRunrDashboard running on port 8000
     *
     * @return the default JobRunrDashboard configuration
     */
    public static JobRunrDashboardWebServerConfiguration usingStandardDashboardConfiguration() {
        return new JobRunrDashboardWebServerConfiguration();
    }

    /**
     * Specifies the port on which the JobRunrDashboard will run
     *
     * @param port the port on which the JobRunrDashboard will run
     * @return the same configuration instance which provides a fluent api
     */
    public JobRunrDashboardWebServerConfiguration andPort(int port) {
        this.port = port;
        return this;
    }

    /**
     * Adds basic authentication to the dashboard using the provided username and password.
     * <span class="strong">WARNING</span> the password will be stored in clear text and if you are using http, it can be easily intercepted.
     *
     * @param username the login which the JobRunrDashboard will ask
     * @param password the password which the JobRunrDashboard will ask
     * @return the same configuration instance which provides a fluent api
     */
    public JobRunrDashboardWebServerConfiguration andBasicAuthentication(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }


    /**
     * Allows to opt-out of anonymous usage statistics. This setting is true by default and sends only the total amount of succeeded jobs processed
     * by your cluster per day to show a counter on the JobRunr website for marketing purposes.
     *
     * @return the same configuration instance which provides a fluent api
     */
    public JobRunrDashboardWebServerConfiguration andAllowAnonymousDataUsage(boolean allowAnonymousDataUsage) {
        this.allowAnonymousDataUsage = allowAnonymousDataUsage;
        return this;
    }

    /**
     * Connects the dashboard to the hosted JobRunr MCP server (mcp.jobrunr.io) so AI agents can inspect and operate this cluster.
     * The connection is outbound only. Job parameter values are redacted unless {@link #andMcpJobParameterRedaction(boolean)} says otherwise.
     *
     * @param connectorToken the connector token from your mcp.jobrunr.io account, or null to keep the connector disabled
     * @return the same configuration instance which provides a fluent api
     */
    public JobRunrDashboardWebServerConfiguration andMcpConnector(String connectorToken) {
        return andMcpConnector(connectorToken, McpHubConnector.DEFAULT_HUB_URL);
    }

    /**
     * Same as {@link #andMcpConnector(String)} but connects to the given JobRunr MCP server.
     *
     * @param connectorToken the connector token, or null to keep the connector disabled
     * @param hubUrl         the url of the JobRunr MCP server
     * @return the same configuration instance which provides a fluent api
     */
    public JobRunrDashboardWebServerConfiguration andMcpConnector(String connectorToken, String hubUrl) {
        this.mcpConnectorToken = connectorToken;
        this.mcpHubUrl = hubUrl;
        return this;
    }

    /**
     * Whether the MCP connector replaces job parameter values by {@code <redacted>} before they leave the JVM. This is true by
     * default; set it to false to let your agents see the job parameter values.
     *
     * @param redactJobParameters whether job parameter values are redacted
     * @return the same configuration instance which provides a fluent api
     */
    public JobRunrDashboardWebServerConfiguration andMcpJobParameterRedaction(boolean redactJobParameters) {
        this.mcpRedactJobParameters = redactJobParameters;
        return this;
    }
}
