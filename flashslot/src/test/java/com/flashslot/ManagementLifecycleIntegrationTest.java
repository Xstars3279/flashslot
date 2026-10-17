package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST", matches="true")
@SpringBootTest
@AutoConfigureMockMvc
class ManagementLifecycleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.springframework.security.crypto.password.PasswordEncoder passwords;
    static final String PASSWORD="Private-test-password!";
    static final String NEXT="Changed-test-password!";
    static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    final Instant now=Instant.parse("2026-10-16T00:00:00Z");
    long uid, aid;
    String name, admin, userToken, adminToken;

    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenAnswer(c -> Clock.fixed(now,c.getArgument(0)));
        name="managed_"+UUID.randomUUID(); admin="manager_"+UUID.randomUUID();
        uid=account(name,"USER"); aid=account(admin,"ADMIN");
        userToken=login(name,PASSWORD); adminToken=login(admin,PASSWORD);
    }

    @Test void logoutRevokesOnlyThePresentedTokenIncludingItsCopiedValue() throws Exception {
        String second=login(name,PASSWORD);
        assertThat(second).isNotEqualTo(userToken);
        mvc.perform(write(post("/api/v1/auth/logout"),userToken,Map.of())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+userToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+second)).andExpect(status().isOk());
    }

    @Test void passwordChangeChecksOldPasswordAndRevokesAllPreviousTokens() throws Exception {
        mvc.perform(write(post("/api/v1/me/password"),userToken,Map.of("currentPassword","wrong","newPassword",NEXT))).andExpect(status().isBadRequest());
        String second=login(name,PASSWORD);
        mvc.perform(write(post("/api/v1/me/password"),userToken,Map.of("currentPassword",PASSWORD,"newPassword",NEXT))).andExpect(status().isOk());
        for(String token:List.of(userToken,second)) mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(name,PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(login(name,NEXT)).isNotBlank();
    }

    @Test void adminCreatesListsResetsAndDisablesAccountsWithoutExposingSecrets() throws Exception {
        String createdName="created_"+UUID.randomUUID();
        var body=Map.of("username",createdName,"password",PASSWORD,"role","USER","reason","创建试用账号");
        mvc.perform(write(post("/api/v1/admin/users"),userToken,body)).andExpect(status().isForbidden());
        var result=mvc.perform(write(post("/api/v1/admin/users"),adminToken,body)).andExpect(status().isCreated()).andReturn();
        long target=json.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD,"passwordHash");
        mvc.perform(write(post("/api/v1/admin/users"),adminToken,body)).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/admin/users").param("username",createdName).header("Authorization","Bearer "+adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("data.items[0].username").value(createdName));
        String old=login(createdName,PASSWORD);
        mvc.perform(write(post("/api/v1/admin/users/"+target+"/password-reset"),adminToken,Map.of("newPassword",NEXT,"reason","用户申请重置"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+old)).andExpect(status().isUnauthorized());
        String changed=login(createdName,NEXT);
        mvc.perform(write(patch("/api/v1/admin/users/"+target+"/status"),adminToken,Map.of("enabled",false,"reason","停用"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+changed)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(createdName,NEXT)).andExpect(status().isUnauthorized());
        mvc.perform(write(patch("/api/v1/admin/users/"+target+"/status"),adminToken,Map.of("enabled",true,"reason","启用"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+changed)).andExpect(status().isUnauthorized());
        assertThat(login(createdName,NEXT)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE target_id=? AND operation IN ('ACCOUNT_CREATE','PASSWORD_RESET','ACCOUNT_STATUS')",Integer.class,Long.toString(target))).isEqualTo(4);
    }

    @Test void rejectsSelfDisableAndInvalidOrOversizedPasswords() throws Exception {
        mvc.perform(write(patch("/api/v1/admin/users/"+aid+"/status"),adminToken,Map.of("enabled",false,"reason","误操作"))).andExpect(status().isConflict());
        for(String password:List.of("short","密".repeat(25)))
            mvc.perform(write(post("/api/v1/me/password"),userToken,Map.of("currentPassword",PASSWORD,"newPassword",password))).andExpect(status().isBadRequest());
    }

    @Test void administratorOnlyManagesAndCannotUsePersonalBookingFlows() throws Exception {
        long resource=createResource();
        var body=Map.of("resourceId",resource,"bookDate","2026-10-17","startSlot",18,"endSlot",20,"purpose","STUDY");
        for(String path:List.of("/api/v1/bookings","/api/v1/waitlists"))
            mvc.perform(write(post(path),adminToken,body).header("Idempotency-Key",UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        for(String path:List.of("/api/v1/me/bookings","/api/v1/me/waitlists","/api/v1/me/notifications"))
            mvc.perform(get(path).header("Authorization","Bearer "+adminToken)).andExpect(status().isForbidden());
        var created=mvc.perform(write(post("/api/v1/bookings"),userToken,body).header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andReturn();
        String order=json.readTree(created.getResponse().getContentAsString()).at("/data/orderNo").asText();
        mvc.perform(write(post("/api/v1/bookings/"+order+"/check-in"),adminToken,Map.of("checkInCode","test")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/bookings/"+order).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
        mvc.perform(write(post("/api/v1/bookings/"+order+"/cancel"),adminToken,Map.of("reason","设备故障，管理员取消"))
            .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE user_id=?",Integer.class,aid)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_record WHERE user_id=?",Integer.class,aid)).isZero();
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
    }

    @Test void databaseAdminRoleCannotUseAnOldUserIdentityToCreateBookingsOrWaitlists() throws Exception {
        long resource=createResource();
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?",uid);
        var body=Map.of("resourceId",resource,"bookDate","2026-10-17","startSlot",18,"endSlot",20,"purpose","STUDY");
        for(String path:List.of("/api/v1/bookings","/api/v1/waitlists"))
            mvc.perform(write(post(path),userToken,body).header("Idempotency-Key",UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE user_id=?",Integer.class,uid)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_record WHERE user_id=?",Integer.class,uid)).isZero();
    }

    @Test void pausedResourceRemainsManageableAndRetirementIsTerminalAndVersioned() throws Exception {
        long resource=createResource();
        mvc.perform(write(patch("/api/v1/admin/resources/"+resource+"/status"),userToken,Map.of("status","PAUSED","version",0,"reason","维护"))).andExpect(status().isForbidden());
        changeStatus(resource,"PAUSED",0,200);
        mvc.perform(get("/api/v1/admin/resources").param("cursor",Long.toString(resource-1)).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk())
            .andExpect(jsonPath("data.items[?(@.id == "+resource+")].status").value(org.hamcrest.Matchers.contains("PAUSED")));
        changeStatus(resource,"ACTIVE",0,409);
        mvc.perform(write(post("/api/v1/bookings"),userToken,Map.of("resourceId",resource,"bookDate","2026-10-17","startSlot",18,"endSlot",20,"purpose","STUDY"))
            .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isConflict());
        changeStatus(resource,"ACTIVE",1,200);
        changeStatus(resource,"RETIRED",2,200);
        changeStatus(resource,"ACTIVE",3,409);
    }

    @Test void retirementRejectsExistingFutureBookings() throws Exception {
        long resource=createResource();
        mvc.perform(write(post("/api/v1/bookings"),userToken,Map.of("resourceId",resource,"bookDate","2026-10-17","startSlot",18,"endSlot",20,"purpose","STUDY"))
            .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isCreated());
        changeStatus(resource,"RETIRED",0,409);
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id=?",String.class,resource)).isEqualTo("ACTIVE");
    }

    @Test void retirementRejectsWaitlistsHandoffAndCheckedInOrdersButAllowsHistoricalUse() throws Exception {
        long waiting=createResource();
        jdbc.update("INSERT INTO waitlist_record(waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at) VALUES (?,?,?,'2026-10-17',18,20,'STUDY','2026-10-17 00:50:00')","WL_"+UUID.randomUUID(),uid,waiting);
        changeStatus(waiting,"RETIRED",0,409);
        jdbc.update("UPDATE waitlist_record SET expire_at='2026-10-15 00:00:00' WHERE resource_id=?",waiting);
        changeStatus(waiting,"RETIRED",0,200);
        long active=createResource();
        var created=mvc.perform(write(post("/api/v1/bookings"),userToken,Map.of("resourceId",active,"bookDate","2026-10-17","startSlot",18,"endSlot",20,"purpose","STUDY"))
            .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isCreated()).andReturn();
        String order=json.readTree(created.getResponse().getContentAsString()).at("/data/orderNo").asText();
        jdbc.update("UPDATE booking_order SET status='CHECKED_IN',checked_in_at=start_at WHERE order_no=?",order);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-17T01:15:00Z"));
        adminToken=login(admin,PASSWORD); userToken=login(name,PASSWORD);
        changeStatus(active,"RETIRED",0,409);
        mvc.perform(write(post("/api/v1/bookings/"+order+"/cancel"),userToken,Map.of()).header("Idempotency-Key","finish"))
            .andExpect(status().isOk());
        changeStatus(active,"RETIRED",0,409);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-17T03:00:00Z"));
        changeStatus(active,"RETIRED",0,200);
    }

    @Test void retirementAndBookingCreationHaveOneConsistentWinner() throws Exception {
        long resource=createResource();
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var retire=pool.submit(() -> { start.await(); return mvc.perform(write(patch("/api/v1/admin/resources/"+resource+"/status"),adminToken,Map.of("status","RETIRED","version",0,"reason","并发退役"))).andReturn().getResponse().getStatus(); });
            var book=pool.submit(() -> { start.await(); return mvc.perform(write(post("/api/v1/bookings"),userToken,Map.of("resourceId",resource,"bookDate","2026-10-17","startSlot",18,"endSlot",20,"purpose","STUDY")).header("Idempotency-Key","race-book")).andReturn().getResponse().getStatus(); });
            start.countDown();
            int retired=retire.get(15,java.util.concurrent.TimeUnit.SECONDS), booked=book.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(retired==200 && booked==409 || retired==409 && booked==201).isTrue();
            if(retired==200) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE resource_id=?",Integer.class,resource)).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void passwordResetDuringAuthenticationCannotIssueAUsableStaleToken() throws Exception {
        var authenticated=new java.util.concurrent.CountDownLatch(1);
        var resume=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            boolean matches=(boolean)invocation.callRealMethod();
            authenticated.countDown();
            if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return matches;
        }).when(passwords).matches(PASSWORD,HASH);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(() -> mvc.perform(loginRequest(name,PASSWORD)).andReturn().getResponse().getStatus());
            assertThat(authenticated.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mvc.perform(write(post("/api/v1/admin/users/"+uid+"/password-reset"),adminToken,Map.of("newPassword",NEXT,"reason","并发重置"))).andExpect(status().isOk());
            resume.countDown();
            assertThat(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(401);
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    long account(String username,String role) {
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,?,?)",username,HASH,role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,username);
    }
    MockHttpServletRequestBuilder loginRequest(String username,String password) throws Exception {
        return post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("198.51.100."+(uid%200+1)); return r; })
            .contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",password)));
    }
    String login(String username,String password) throws Exception {
        var response=mvc.perform(loginRequest(username,password)).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).at("/data/accessToken").asText();
    }
    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request,String token,Object body) throws Exception {
        return request.header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body));
    }
    long createResource() throws Exception {
        var result=mvc.perform(write(post("/api/v1/admin/resources"),adminToken,Map.of("name","测试设备","location","A201","defaultOpenStartSlot",0,"defaultOpenEndSlot",48,"reason","验收")))
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong();
    }
    void changeStatus(long resource,String status,long version,int expected) throws Exception {
        mvc.perform(write(patch("/api/v1/admin/resources/"+resource+"/status"),adminToken,Map.of("status",status,"version",version,"reason","状态维护")))
            .andExpect(status().is(expected));
    }
}
