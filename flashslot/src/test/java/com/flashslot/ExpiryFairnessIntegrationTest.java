package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.flashslot.booking.service.BookingExpiryService;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.waitlist.service.WaitlistExpiryService;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;

@SpringBootTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class ExpiryFairnessIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingExpiryService bookings;
    @Autowired WaitlistExpiryService waitlists;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxEventMapper events;

    @Test void failedFullBatchDoesNotStarveLaterOrdersOrWaitlistsAndCanBeRetried() {
        // All 101 records share a due time, exercising the ID tie-breaker too.
        when(clock.instant()).thenReturn(Instant.parse("2020-01-02T00:00:00Z"));
        String prefix="fair_"+UUID.randomUUID()+"_";
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','USER')",prefix);
        long user=jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,prefix);
        for(int i=0;i<101;i++) {
            String name=prefix+i;
            jdbc.update("INSERT INTO resource(name,location) VALUES (?,'test')",name);
            long resource=jdbc.queryForObject("SELECT id FROM resource WHERE name=?",Long.class,name);
            jdbc.update("INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2020-01-01')",resource);
            jdbc.update("""
                INSERT INTO booking_order(order_no,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,
                  status,purpose,source,start_at,check_in_open_at,check_in_deadline,end_at)
                VALUES (?,?,?,'2020-01-01',18,20,2,'CONFIRMED','STUDY','DIRECT',
                  '2020-01-01 01:00:00','2020-01-01 00:50:00','2020-01-01 01:15:00','2020-01-01 02:00:00')
                """,name,user,resource);
            jdbc.update("""
                INSERT INTO waitlist_record(waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at)
                VALUES (?,?,?,'2020-01-01',18,20,'STUDY','2020-01-01 01:15:00')
                """,name,user,resource);
        }
        String healthy=prefix+100;
        doThrow(new IllegalStateException("persistent event storage failure for first 100 records"))
            .when(events).insert(argThat((OutboxEvent e) -> e!=null && e.getAggregateId().startsWith(prefix)
                && !healthy.equals(e.getAggregateId())));
        try {
            bookings.scanDueOrders(); waitlists.scanExpired();
            bookings.scanDueOrders(); waitlists.scanExpired();
            assertThat(jdbc.queryForObject("SELECT status FROM booking_order WHERE order_no=?",String.class,healthy)).isEqualTo("NO_SHOW");
            assertThat(jdbc.queryForObject("SELECT status FROM waitlist_record WHERE waitlist_no=?",String.class,healthy)).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE order_no LIKE ? AND status='CONFIRMED'",Integer.class,prefix+"%")).isEqualTo(100);
            reset(events);
            bookings.scanDueOrders(); waitlists.scanExpired();
            bookings.scanDueOrders(); waitlists.scanExpired();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE order_no LIKE ? AND status='NO_SHOW'",Integer.class,prefix+"%")).isEqualTo(101);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_record WHERE waitlist_no LIKE ? AND status='EXPIRED'",Integer.class,prefix+"%")).isEqualTo(101);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE user_id=?",Integer.class,user)).isEqualTo(101);
        } finally { reset(events); }
    }
}
