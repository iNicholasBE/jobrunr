package org.jobrunr.dashboard.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobParameterRedactorTest {

    @Test
    void redactsJobParameterValuesButKeepsTheirTypes() {
        String json = "{\"jobName\":\"Send mail\",\"jobDetails\":{\"className\":\"org.acme.MailService\",\"methodName\":\"send\"," +
                "\"jobParameters\":[{\"className\":\"java.lang.String\",\"actualClassName\":\"java.lang.String\",\"object\":\"jane@acme.com\"}," +
                "{\"className\":\"java.lang.Integer\",\"actualClassName\":\"java.lang.Integer\",\"object\":42}]}}";

        String redacted = JobParameterRedactor.redact(json);

        assertThat(redacted)
                .doesNotContain("jane@acme.com")
                .doesNotContain("42")
                .isEqualTo("{\"jobName\":\"Send mail\",\"jobDetails\":{\"className\":\"org.acme.MailService\",\"methodName\":\"send\"," +
                        "\"jobParameters\":[{\"className\":\"java.lang.String\",\"actualClassName\":\"java.lang.String\",\"object\":\"<redacted>\"}," +
                        "{\"className\":\"java.lang.Integer\",\"actualClassName\":\"java.lang.Integer\",\"object\":\"<redacted>\"}]}}");
    }

    @Test
    void redactsComplexParameterValuesInEveryJobOfAPage() {
        String json = "{\"total\":2,\"items\":[" +
                "{\"jobDetails\":{\"jobParameters\":[{\"className\":\"org.acme.Customer\",\"object\":{\"name\":\"Jane\",\"tags\":[\"vip\",{\"x\":\"}\"}]}}]}}," +
                "{\"jobDetails\":{\"jobParameters\":[{\"className\":\"java.util.List\",\"object\":[\"a \\\"quoted\\\" value\", null]}]}}" +
                "]}";

        String redacted = JobParameterRedactor.redact(json);

        assertThat(redacted)
                .doesNotContain("Jane", "vip", "quoted")
                .isEqualTo("{\"total\":2,\"items\":[" +
                        "{\"jobDetails\":{\"jobParameters\":[{\"className\":\"org.acme.Customer\",\"object\":\"<redacted>\"}]}}," +
                        "{\"jobDetails\":{\"jobParameters\":[{\"className\":\"java.util.List\",\"object\":\"<redacted>\"}]}}" +
                        "]}");
    }

    @Test
    void keepsObjectKeysOutsideOfJobParameters() {
        String json = "{ \"object\" : \"keep me\", \"jobParameters\" : [ ], \"metadata\" : { \"object\" : 1 } }";

        assertThat(JobParameterRedactor.redact(json)).isEqualTo(json);
    }

    @Test
    void returnsJsonWithoutJobParametersUnchanged() {
        String json = "[{\"id\":\"server-1\",\"running\":true}]";

        assertThat(JobParameterRedactor.redact(json)).isSameAs(json);
    }

    @Test
    void returnsNullWhenTheJsonCannotBeParsed() {
        assertThat(JobParameterRedactor.redact("{\"jobParameters\":[{\"object\":\"unterminated")).isNull();
        assertThat(JobParameterRedactor.redact("{\"jobParameters\":[]} trailing")).isNull();
    }
}
