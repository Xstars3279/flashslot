package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.support.TestUser;
import com.flashslot.waitlist.service.ReleaseJobService;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;

@SpringBootTest @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class ReviewSemanticsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ReleaseJobService releases;
    @MockitoBean Clock clock;
    @MockitoSpyBean com.flashslot.messaging.mapper.OutboxEventMapper events;
    String owner,other;
    long resource,otherId;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T00:01:00Z"));
        owner="review_"+UUID.randomUUID(); other="review_"+UUID.randomUUID();
        for(String name:new String[]{owner,other}) jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','USER')",name);
        otherId=jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,other);
        String name="review_device_"+UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'review')",name);
        resource=jdbc.queryForObject("SELECT id FROM resource WHERE name=?",Long.class,name);
        calendar();
    }
    void calendar() { jdbc.update("INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')",resource); }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(String path,String who,String key) {
        return post("/api/v1/"+path).with(new TestUser(jdbc,who)).with(csrf()).header("Idempotency-Key",key)
            .contentType("application/json").content("{\"resourceId\":"+resource+",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":22,\"purpose\":\"STUDY\"}");
    }
    String book() throws Exception {
        return json.readTree(mvc.perform(write("bookings",owner,"book")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/orderNo").asText();
    }
    void cancel(String order) throws Exception {
        mvc.perform(post("/api/v1/bookings/"+order+"/cancel").with(new TestUser(jdbc,owner)).with(csrf())
            .header("Idempotency-Key","cancel").contentType("application/json").content("{}")).andExpect(status().isOk());
    }
    @Test void cancellationWithoutWaitingCandidatesImmediatelyRestoresDirectBooking() throws Exception {
        String order=book(); cancel(order); cancel(order);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_occupation WHERE resource_id=?",Integer.class,resource)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM release_job WHERE resource_id=?",String.class,resource)).isEqualTo("RELEASED");
        mvc.perform(write("bookings",other,"replace")).andExpect(status().isCreated());
    }
    @Test void immediateReleaseRollsBackTogetherWithOrderAndCanRetryOriginalKey() throws Exception {
        String order=book();
        doThrow(new IllegalStateException("release event cannot commit")).when(events).insert(
            argThat((com.flashslot.messaging.entity.OutboxEvent e) -> e!=null && "RELEASE_COMPLETED".equals(e.getEventType())));
        mvc.perform(post("/api/v1/bookings/"+order+"/cancel").with(new TestUser(jdbc,owner)).with(csrf())
            .header("Idempotency-Key","cancel").contentType("application/json").content("{}"))
            .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT status FROM booking_order WHERE order_no=?",String.class,order)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_occupation WHERE resource_id=?",Integer.class,resource)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_job WHERE resource_id=?",Integer.class,resource)).isZero();
        reset(events); cancel(order);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_occupation WHERE resource_id=?",Integer.class,resource)).isZero();
    }
    @Test void onlyExpiredCandidatesDoNotHoldCancelledSlots() throws Exception {
        String order=book();
        mvc.perform(write("waitlists",other,"wait")).andExpect(status().isCreated());
        jdbc.update("UPDATE waitlist_record SET expire_at='2026-10-15 00:00:00' WHERE resource_id=?",resource);
        cancel(order);
        mvc.perform(write("bookings",other,"replace")).andExpect(status().isCreated());
    }
    @Test void missingCalendarCanBeRetriedWithSameBookingKey() throws Exception {
        jdbc.update("DELETE FROM resource_day WHERE resource_id=?",resource);
        mvc.perform(write("bookings",other,"retry")).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency WHERE user_id=? AND request_key='retry'",Integer.class,otherId)).isZero();
        calendar();
        mvc.perform(write("bookings",other,"retry")).andExpect(status().isCreated());
    }
    @Test void missingCalendarCanBeRetriedWithSameWaitlistKey() throws Exception {
        jdbc.update("DELETE FROM resource_day WHERE resource_id=?",resource);
        mvc.perform(write("waitlists",other,"retry")).andExpect(status().isServiceUnavailable());
        calendar(); book();
        mvc.perform(write("waitlists",other,"retry")).andExpect(status().isCreated());
    }
    @Test void oldCachedCalendarFailureIsReevaluatedButPayloadConflictStillRejects() throws Exception {
        String canonical=resource+"\n2026-10-16\n18\n22\nSTUDY";
        jdbc.update("""
            INSERT INTO api_idempotency(user_id,operation,request_key,payload_hash,expires_at,result_http_status,result_code,result_json)
            VALUES (?,'CREATE_BOOKING','legacy',UNHEX(SHA2(?,256)),'2026-10-22 00:00:00',503,'CALENDAR_NOT_READY',JSON_OBJECT('code',0,'msg','old calendar failure'))
            """,otherId,canonical);
        mvc.perform(write("bookings",other,"legacy").content("{\"resourceId\":"+resource+",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":20,\"purpose\":\"STUDY\"}"))
            .andExpect(status().isConflict());
        mvc.perform(write("bookings",other,"legacy")).andExpect(status().isCreated());
    }
    @Test void machineReadableBusinessCodesSurviveFirstResponseAndReplay() throws Exception {
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?",resource);
        for(int i=0;i<2;i++) mvc.perform(write("bookings",other,"closed"))
            .andExpect(status().isConflict()).andExpect(jsonPath("code").value(0))
            .andExpect(jsonPath("errorCode").value("RESOURCE_CLOSED"));
        mvc.perform(get("/api/v1/me/profile")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("errorCode").value("UNAUTHENTICATED"));
    }
    @Test void pausedResourceKeepsCandidateWaitingUntilResume() throws Exception {
        String order=book();
        mvc.perform(write("waitlists",other,"wait")).andExpect(status().isCreated());
        cancel(order);
        long job=jdbc.queryForObject("SELECT id FROM release_job WHERE resource_id=?",Long.class,resource);
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?",resource);
        releases.process(job);
        assertThat(jdbc.queryForObject("SELECT status FROM waitlist_record WHERE resource_id=?",String.class,resource)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT status FROM release_job WHERE id=?",String.class,job)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT next_run_at FROM release_job WHERE id=?",java.sql.Timestamp.class,job).toInstant())
            .isEqualTo(Instant.parse("2026-10-15T00:06:00Z"));
        jdbc.update("UPDATE resource SET status='ACTIVE' WHERE id=?",resource);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T00:06:00Z"));
        releases.process(job);
        assertThat(jdbc.queryForObject("SELECT status FROM waitlist_record WHERE resource_id=?",String.class,resource)).isEqualTo("MATCHED");
    }
    @Test void pausedResourceStillExpiresCandidatesAndReleasesAfterCutoff() throws Exception {
        String order=book(); mvc.perform(write("waitlists",other,"wait")).andExpect(status().isCreated());
        cancel(order);
        long job=jdbc.queryForObject("SELECT id FROM release_job WHERE resource_id=?",Long.class,resource);
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?",resource);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:00:00Z"));
        releases.process(job);
        assertThat(jdbc.queryForObject("SELECT status FROM waitlist_record WHERE resource_id=?",String.class,resource)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM release_job WHERE id=?",String.class,job)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE release_job_id=?",Integer.class,job)).isZero();
    }
}
