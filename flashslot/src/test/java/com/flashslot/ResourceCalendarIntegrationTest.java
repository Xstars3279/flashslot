package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.flashslot.resource.service.ResourceCalendarService;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest(properties = "app.resource-calendar.scheduling-enabled=false")
class ResourceCalendarIntegrationTest {
    @Autowired ResourceCalendarService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    long id;
    // UTC 16:01 已是上海第二天，窗口必须从上海日期起算。
    final Instant now = Instant.parse("2026-10-16T16:01:00Z");
    final LocalDate today = LocalDate.of(2026, 10, 17);

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(now);
        String name = "rolling_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location,default_open_start_slot,default_open_end_slot) VALUES (?,'lab',18,40)", name);
        id = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
    }

    @Test
    void recoversMissingDaysWithoutOverwritingAdminConfigurationOrHistory() {
        jdbc.update("INSERT INTO resource_day(resource_id,book_date,enabled,open_start_slot,open_end_slot,revision) VALUES (?,?,0,20,30,7),(?,?,1,16,44,2)", id, today, id, today.minusDays(30));
        service.ensureWindow(id);
        service.ensureWindow(id);
        assertThat(count()).isEqualTo(15);
        assertThat(jdbc.queryForMap("SELECT enabled,open_start_slot,open_end_slot,revision FROM resource_day WHERE resource_id=? AND book_date=?", id, today))
            .containsEntry("enabled", false).containsEntry("open_start_slot", 20).containsEntry("open_end_slot", 30).containsEntry("revision", 7L);
        assertThat(jdbc.queryForObject("SELECT MAX(book_date) FROM resource_day WHERE resource_id=?", LocalDate.class, id)).isEqualTo(today.plusDays(13));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_day WHERE resource_id=? AND book_date>? AND open_start_slot=18 AND open_end_slot=40", Integer.class, id, today)).isEqualTo(13);
    }

    @Test
    void nextDayExtendsWindowUsingLatestDefaultsAndRetainsExistingDays() {
        service.ensureWindow(id);
        when(clock.instant()).thenReturn(now.plusSeconds(86400));
        jdbc.update("UPDATE resource SET default_open_start_slot=22 WHERE id=?", id);
        service.ensureWindow(id);
        assertThat(count()).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT open_start_slot FROM resource_day WHERE resource_id=? AND book_date=?", Integer.class, id, today.plusDays(14))).isEqualTo(22);
        assertThat(jdbc.queryForObject("SELECT open_start_slot FROM resource_day WHERE resource_id=? AND book_date=?", Integer.class, id, today)).isEqualTo(18);
    }

    @Test
    void concurrentReplenishmentDoesNotDuplicateDays() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Callable<Void> call = () -> { gate.await(); service.ensureWindow(id); return null; };
            var first = pool.submit(call);
            var second = pool.submit(call);
            gate.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(count()).isEqualTo(14);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void retiredAndMissingResourcesAreSkippedButPausedDevicesRetainCalendars() {
        service.ensureWindow(Long.MAX_VALUE);
        jdbc.update("UPDATE resource SET status='RETIRED' WHERE id=?", id);
        service.ensureWindow(id);
        assertThat(count()).isZero();
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?", id);
        service.ensureWindow(id);
        assertThat(count()).isEqualTo(14);
    }

    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM resource_day WHERE resource_id=?", Integer.class, id); }
}
