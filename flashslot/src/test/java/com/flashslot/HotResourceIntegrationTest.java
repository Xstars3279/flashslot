package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import com.flashslot.resource.mapper.*;
import com.flashslot.resource.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties="app.cache-lock.watchdog-timeout-ms=1500")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_REDIS_TEST",matches="true")
class HotResourceIntegrationTest {
    @Autowired ResourceScheduleService schedules;
    @Autowired ResourceService resources;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.redisson.api.RedissonClient locks;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoSpyBean ScheduleReadMapper reads;
    @MockitoSpyBean ResourceMapper mapper;
    @MockitoSpyBean com.flashslot.operations.mapper.AdminAuditMapper audits;
    long resource;
    LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
    @BeforeEach void setup() {
        String name="hot_"+UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'hot test')",name);
        resource=jdbc.queryForObject("SELECT id FROM resource WHERE name=?",Long.class,name);
        jdbc.update("INSERT INTO resource_day(resource_id,book_date) VALUES (?,?)",resource,date);
        clearInvocations(reads,mapper);
    }
    @Test void concurrentColdRequestsRebuildPublicOccupationOnlyOnce() throws Exception {
        doAnswer(call -> { Thread.sleep(150); return List.of(); }).when(reads).findOccupations(eq(resource),eq(date),isNull());
        var pool=Executors.newFixedThreadPool(8); var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> { start.await(); assertThat(schedules.getSchedule(resource,date).getSlots()).hasSize(48); return null; }));
            start.countDown(); for(var future:futures) future.get(15,TimeUnit.SECONDS);
            verify(reads,times(1)).findOccupations(eq(resource),eq(date),isNull());
        } finally { pool.shutdownNow(); }
    }
    @Test void missingResourceUsesShortNegativeCacheAndNewDeviceUsesHotCache() {
        long missing=900000000000L+resource;
        for(int i=0;i<4;i++) org.assertj.core.api.Assertions.assertThatThrownBy(() -> resources.get(missing)).isInstanceOf(com.flashslot.common.web.BusinessException.class);
        verify(mapper,times(1)).selectById(missing);
        assertThat(redis.getExpire("flashslot:resource:"+missing+":missing")).isBetween(1L,15L);
        resources.get(resource); resources.get(resource);
        verify(mapper,times(1)).selectById(resource);
        assertThat(redis.getExpire("flashslot:resource:"+resource+":0")).isBetween(90L,180L);
    }
    @Test void watchdogKeepsLockWhileOwnerStillRunsPastInitialTimeout() throws Exception {
        var lock=locks.getLock("flashslot:test:watchdog:"+UUID.randomUUID());
        assertThat(lock.tryLock(1,TimeUnit.SECONDS)).isTrue();
        var pool=Executors.newSingleThreadExecutor();
        try {
            Thread.sleep(2300);
            assertThat(lock.remainTimeToLive()).isPositive();
            assertThat(pool.submit(() -> {
                boolean acquired=lock.tryLock(100,TimeUnit.MILLISECONDS);
                if(acquired) lock.unlock();
                return acquired;
            }).get(5,TimeUnit.SECONDS)).isFalse();
        } finally { if(lock.isHeldByCurrentThread()) lock.unlock(); pool.shutdownNow(); }
    }
    @Test void jitterSpreadsExpiryAndHotDevicesHaveLongerOccupationTtl() {
        var ttls=new HashSet<Long>();
        for(int i=0;i<12;i++) {
            redis.delete("flashslot:schedule:"+resource+":"+date+":0");
            schedules.getSchedule(resource,date);
            long ttl=redis.getExpire("flashslot:schedule:"+resource+":"+date+":0");
            assertThat(ttl).isBetween(50L,90L); ttls.add(ttl);
        }
        assertThat(ttls).hasSizeGreaterThan(1);
    }
    @Test void administratorChangeInvalidatesMetadataAndNegativeCache() throws Exception {
        String admin="hot_admin_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','ADMIN')",admin);
        resources.get(resource);
        mvc.perform(patch("/api/v1/admin/resources/"+resource).with(new com.flashslot.support.TestUser(jdbc,admin).roles("ADMIN")).with(csrf())
            .contentType("application/json").content("{\"name\":\"changed hot device\",\"version\":0,\"reason\":\"缓存失效验证\"}"))
            .andExpect(status().isOk());
        assertThat(resources.get(resource).getName()).isEqualTo("changed hot device");
        long next=jdbc.queryForObject("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resource'",Long.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resources.get(next)).isInstanceOf(com.flashslot.common.web.BusinessException.class);
        var result=mvc.perform(post("/api/v1/admin/resources").with(new com.flashslot.support.TestUser(jdbc,admin).roles("ADMIN")).with(csrf())
            .contentType("application/json").content("{\"name\":\"new hot device\",\"location\":\"lab\",\"defaultOpenStartSlot\":16,\"defaultOpenEndSlot\":44,\"reason\":\"新增缓存验证\"}"))
            .andExpect(status().isCreated()).andReturn();
        long id=json.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong();
        assertThat(id).isEqualTo(next);
        // 模拟新增后晚到的旧空值回填：数据库已有version=0时，不再读取missing键。
        redis.opsForValue().set("flashslot:resource:"+id+":missing","null",Duration.ofSeconds(10));
        assertThat(resources.get(id).getName()).isEqualTo("new hot device");
    }
    @Test void committedDatabaseVersionIgnoresOldCacheWhenDeletionWasMissed() {
        String oldName=resources.get(resource).getName();
        jdbc.update("UPDATE resource SET name='version one',version=version+1 WHERE id=?",resource);
        assertThat(resources.get(resource).getName()).isEqualTo("version one");
        assertThat(redis.opsForValue().get("flashslot:resource:"+resource+":0")).contains(oldName);
        assertThat(redis.opsForValue().get("flashslot:resource:"+resource+":1")).contains("version one");
    }
    @Test void rollbackKeepsDatabaseVersionAndCacheVersionUnchanged() throws Exception {
        String oldName=resources.get(resource).getName();
        String admin="rollback_admin_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','ADMIN')",admin);
        doThrow(new IllegalStateException("injected audit rollback")).when(audits).insert(any(com.flashslot.operations.entity.AdminAudit.class));
        mvc.perform(patch("/api/v1/admin/resources/"+resource).with(new com.flashslot.support.TestUser(jdbc,admin).roles("ADMIN")).with(csrf())
            .contentType("application/json").content("{\"name\":\"rolled back\",\"version\":0,\"reason\":\"事务回滚验证\"}"))
            .andExpect(status().isInternalServerError());
        assertThat(mapper.findVersion(resource)).isZero();
        assertThat(resources.get(resource).getName()).isEqualTo(oldName);
        assertThat(redis.opsForValue().get("flashslot:resource:"+resource+":0")).contains(oldName);
        assertThat(redis.hasKey("flashslot:resource:"+resource+":1")).isFalse();
    }
    @Test void delayedOldMetadataOnlyFillsOldVersionAndDoesNotBlockNewVersion() throws Exception {
        var old=mapper.selectById(resource);
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            if(first.compareAndSet(true,false)) { started.countDown(); release.await(10,TimeUnit.SECONDS); return old; }
            return jdbc.queryForObject("SELECT * FROM resource WHERE id=?",(rs,n) -> {
                var value=new com.flashslot.resource.entity.Resource();
                value.setId(rs.getLong("id")); value.setName(rs.getString("name")); value.setLocation(rs.getString("location"));
                value.setStatus(rs.getString("status")); value.setVersion(rs.getLong("version"));
                value.setDefaultOpenStartSlot(rs.getInt("default_open_start_slot")); value.setDefaultOpenEndSlot(rs.getInt("default_open_end_slot"));
                value.setCreatedAt(Instant.now()); value.setUpdatedAt(Instant.now()); return value;
            },resource);
        }).when(mapper).selectById(resource);
        String admin="fence_admin_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','ADMIN')",admin);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var slow=pool.submit(() -> resources.get(resource));
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            mvc.perform(patch("/api/v1/admin/resources/"+resource).with(new com.flashslot.support.TestUser(jdbc,admin).roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"name\":\"new fenced name\",\"version\":0,\"reason\":\"慢回填竞态\"}"))
                .andExpect(status().isOk());
            // 更新已提交，旧回填仍被阻塞，新请求应立即读取新版本键。
            assertThat(resources.get(resource).getName()).isEqualTo("new fenced name");
            assertThat(redis.opsForValue().get("flashslot:resource:"+resource+":1")).contains("new fenced name");
            release.countDown(); slow.get(10,TimeUnit.SECONDS);
            assertThat(redis.opsForValue().get("flashslot:resource:"+resource+":0")).contains(old.getName());
            assertThat(resources.get(resource).getName()).isEqualTo("new fenced name");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void simultaneousBookingsOfHotDeviceStillHaveOneWinnerAndFreshSchedule() throws Exception {
        schedules.getSchedule(resource,date);
        var users=new ArrayList<String>();
        for(int i=0;i<12;i++) {
            String user="hot_user_"+UUID.randomUUID(); users.add(user);
            jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','USER')",user);
        }
        var pool=Executors.newFixedThreadPool(8); var start=new CountDownLatch(1);
        try {
            var results=new ArrayList<Future<Integer>>();
            for(String user:users) results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/bookings").with(new com.flashslot.support.TestUser(jdbc,user)).with(csrf())
                    .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("resourceId",resource,"bookDate",date.toString(),"startSlot",18,"endSlot",20,"purpose","STUDY"))))
                    .andReturn().getResponse().getStatus();
            }));
            start.countDown(); var statuses=new ArrayList<Integer>();
            for(var result:results) statuses.add(result.get(20,TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(201,409);
            assertThat(statuses.stream().filter(status -> status==201).count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE resource_id=?",Integer.class,resource)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_occupation WHERE resource_id=?",Integer.class,resource)).isEqualTo(2);
        assertThat(schedules.getSchedule(resource,date).getSlots().get(18).getState()).isEqualTo("BOOKED");
    }
}
