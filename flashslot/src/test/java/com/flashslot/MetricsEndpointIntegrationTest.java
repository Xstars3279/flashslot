package com.flashslot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="app.metrics.allowed-networks=127.0.0.1/32") @AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class MetricsEndpointIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void trustedPeerCanScrapeRealBusinessMetrics() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(r -> {r.setRemoteAddr("127.0.0.1");return r;}))
            .andExpect(status().isOk()).andExpect(content().string(containsString("flashslot_message_publish_attempts_total")))
            .andExpect(content().string(containsString("flashslot_message_outbox_backlog")));
    }
    @Test void untrustedPeerCannotUseForwardedHeadersToReadMetrics() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(r -> {r.setRemoteAddr("198.51.100.20");return r;})
            .header("X-Real-IP","127.0.0.1").header("X-Forwarded-For","127.0.0.1"))
            .andExpect(status().is4xxClientError());
    }
}
