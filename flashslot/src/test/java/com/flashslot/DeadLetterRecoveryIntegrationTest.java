package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.flashslot.support.TestUser;
import com.flashslot.messaging.service.DeadLetterStore;
import com.flashslot.messaging.mapper.ConsumerInboxMapper;
import com.flashslot.operations.mapper.AdminAuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class DeadLetterRecoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeadLetterStore store;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AdminAuditMapper audit;
    @MockitoSpyBean ConsumerInboxMapper inbox;

    String admin() {
        String name="dead_admin_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','ADMIN')",name);
        return name;
    }
    byte[] message(String event,String name,int version) throws Exception {
        long user=jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,name);
        return json.writeValueAsBytes(Map.of("eventId",event,"eventType","BOOKING_CREATED","aggregateId","BK_private","occurredAt",Instant.now().toString(),
            "payload",Map.of("schemaVersion",version,"userId",user,"data",Map.of("orderNo","BK_private"))));
    }
    void replay(long id,String name,String key,int status) throws Exception {
        mvc.perform(post("/api/v1/admin/dead-letters/"+id+"/replay").with(new TestUser(jdbc,name).roles("ADMIN")).with(csrf())
            .header("Idempotency-Key",key).contentType("application/json").content("{\"reason\":\"已修复依赖故障\"}"))
            .andExpect(status().is(status));
    }
    long deadId(String event) { return jdbc.queryForObject("SELECT id FROM dead_letter WHERE event_id=?",Long.class,event); }
    @Test void recoveryReusesOriginalEventAndRollsBackIfAuditCannotCommit() throws Exception {
        String name=admin(), event=UUID.randomUUID().toString();
        byte[] raw=message(event,name,1);
        store.capture(raw); store.capture(raw);
        long id=deadId(event);
        assertThat(jdbc.queryForObject("SELECT deliveries FROM dead_letter WHERE id=?",Integer.class,id)).isEqualTo(2);
        doThrow(new IllegalStateException("private audit failure")).when(audit).insert(any(com.flashslot.operations.entity.AdminAudit.class));
        replay(id,name,"rollback",500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?",Integer.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM dead_letter WHERE id=?",String.class,id)).isEqualTo("FAILED");
        reset(audit);
        replay(id,name,"success",200); replay(id,name,"success",200); replay(id,name,"another",200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dead_letter WHERE id=?",String.class,id)).isEqualTo("RECOVERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE operation='DEAD_LETTER_REPLAY' AND target_id=?",Integer.class,Long.toString(id))).isEqualTo(1);
    }
    @Test void unsupportedMessagesStayInspectableAndUserCannotReplay() throws Exception {
        String name=admin(), event=UUID.randomUUID().toString();
        store.capture(message(event,name,2));
        long id=deadId(event);
        replay(id,name,"bad-schema",409);
        jdbc.update("UPDATE app_user SET role='USER' WHERE username=?",name);
        replay(id,name,"stale-admin",403);
        assertThat(jdbc.queryForObject("SELECT status FROM dead_letter WHERE id=?",String.class,id)).isEqualTo("FAILED");
        var result=mvc.perform(get("/api/v1/admin/dead-letters").with(new TestUser(jdbc,admin()).roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("BK_private","body","messageHash");
    }
    @Test void adminCanListPersistedConsumptionFailures() throws Exception {
        String name="dead_admin_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','ADMIN')",name);
        mvc.perform(get("/api/v1/admin/dead-letters").with(new TestUser(jdbc,name).roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("data.items").isArray());
        mvc.perform(get("/api/v1/admin/dead-letters")).andExpect(status().isUnauthorized());
    }
}
