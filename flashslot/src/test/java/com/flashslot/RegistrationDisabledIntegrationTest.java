package com.flashslot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="app.registration.development-code-enabled=false")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class RegistrationDisabledIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void publicCapabilitiesDisableRegistrationWithoutExposingCodes() throws Exception {
        mvc.perform(get("/api/v1/auth/capabilities")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("data.registrationEnabled").value(false))
            .andExpect(jsonPath("data.registrationMode").value("DISABLED"));
    }
    @Test void disabledModeNeverExposesCodesOrRegistersAccounts() throws Exception {
        mvc.perform(post("/api/v1/auth/registration/code").contentType("application/json").content("{\"phone\":\"13912345678\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("data").isEmpty());
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{\"phone\":\"13912345678\",\"code\":\"123456\",\"password\":\"test-password-123\"}"))
            .andExpect(status().isServiceUnavailable());
    }
}
