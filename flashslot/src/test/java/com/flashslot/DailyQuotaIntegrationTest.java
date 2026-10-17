package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.support.TestUser;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class DailyQuotaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.flashslot.waitlist.service.ReleaseJobService releaseJobs;
    @MockitoBean Clock clock;
    String username;
    long user, first, second;
    final Instant now=Instant.parse("2026-10-16T00:00:00Z");

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(now);
        username="quota_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','USER')",username);
        user=jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,username);
        first=resource(); second=resource();
    }
    @Test void totalAcrossDevicesIsLimitedAndCancellingFutureSlotsRestoresQuota() throws Exception {
        String order=book(first,"2026-10-17",16,22,201);
        book(second,"2026-10-17",22,24,201);
        book(second,"2026-10-17",24,25,409);
        mvc.perform(post("/api/v1/bookings/"+order+"/cancel").with(new TestUser(jdbc,username)).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        book(second,"2026-10-17",24,30,201);
        book(first,"2026-10-18",16,24,201);
        assertThat(jdbc.queryForObject("SELECT SUM(charged_slots) FROM booking_order WHERE user_id=? AND book_date='2026-10-17'",Integer.class,user)).isEqualTo(8);
    }
    @Test void concurrentDifferentResourcesCannotBothSpendRemainingDailyQuota() throws Exception {
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(() -> { start.await(); return bookingStatus(first,16,22); });
            var b=pool.submit(() -> { start.await(); return bookingStatus(second,22,28); });
            start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            assertThat(jdbc.queryForObject("SELECT SUM(charged_slots) FROM booking_order WHERE user_id=?",Integer.class,user)).isEqualTo(6);
        } finally { pool.shutdownNow(); }
    }
    @Test void waitlistPromotionChecksQuotaAgainAfterAdmission() throws Exception {
        String candidate=username, owner="quota_owner_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash) VALUES (?,'fixture')",owner);
        username=owner;
        String held=book(first,"2026-10-17",16,20,201);
        username=candidate;
        mvc.perform(post("/api/v1/waitlists").with(new TestUser(jdbc,username)).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("resourceId",first,"bookDate","2026-10-17","startSlot",16,"endSlot",20,"purpose","STUDY"))))
            .andExpect(status().isCreated());
        book(second,"2026-10-17",20,28,201);
        mvc.perform(post("/api/v1/bookings/"+held+"/cancel").with(new TestUser(jdbc,owner)).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        long job=jdbc.queryForObject("SELECT r.id FROM release_job r JOIN booking_order b ON b.id=r.source_booking_id WHERE b.order_no=?",Long.class,held);
        releaseJobs.process(job);
        assertThat(jdbc.queryForObject("SELECT reason FROM waitlist_record WHERE user_id=? AND resource_id=?",String.class,user,first)).isEqualTo("DAILY_QUOTA_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT SUM(charged_slots) FROM booking_order WHERE user_id=?",Integer.class,user)).isEqualTo(8);
    }
    @Test void waitlistCannotBecomeABookingAfterOwnerBecomesAdministrator() throws Exception {
        String candidate=username, owner="role_owner_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash) VALUES (?,'fixture')",owner);
        username=owner;
        String held=book(first,"2026-10-17",16,20,201);
        username=candidate;
        mvc.perform(post("/api/v1/waitlists").with(new TestUser(jdbc,username)).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("resourceId",first,"bookDate","2026-10-17","startSlot",16,"endSlot",20,"purpose","STUDY"))))
            .andExpect(status().isCreated());
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?",user);
        mvc.perform(post("/api/v1/bookings/"+held+"/cancel").with(new TestUser(jdbc,owner)).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        long job=jdbc.queryForObject("SELECT r.id FROM release_job r JOIN booking_order b ON b.id=r.source_booking_id WHERE b.order_no=?",Long.class,held);
        releaseJobs.process(job);
        assertThat(jdbc.queryForObject("SELECT reason FROM waitlist_record WHERE user_id=? AND resource_id=?",String.class,user,first)).isEqualTo("ACCOUNT_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE user_id=?",Integer.class,user)).isZero();
    }
    int bookingStatus(long resource,int start,int end) throws Exception {
        return mvc.perform(post("/api/v1/bookings").with(new TestUser(jdbc,username)).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("resourceId",resource,"bookDate","2026-10-17","startSlot",start,"endSlot",end,"purpose","STUDY"))))
            .andReturn().getResponse().getStatus();
    }
    String book(long resource,String date,int start,int end,int expected) throws Exception {
        var result=mvc.perform(post("/api/v1/bookings").with(new TestUser(jdbc,username)).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("resourceId",resource,"bookDate",date,"startSlot",start,"endSlot",end,"purpose","STUDY"))))
            .andExpect(status().is(expected)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).at("/data/orderNo").asText();
    }
    long resource() {
        String name=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO resource(name,location,default_open_start_slot,default_open_end_slot) VALUES (?,'fixture',0,48)",name);
        long id=jdbc.queryForObject("SELECT id FROM resource WHERE name=?",Long.class,name);
        for(String date:List.of("2026-10-17","2026-10-18")) jdbc.update("INSERT INTO resource_day(resource_id,book_date,open_start_slot,open_end_slot) VALUES (?,?,0,48)",id,date);
        return id;
    }
}
