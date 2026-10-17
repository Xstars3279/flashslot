package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"app.history-cleanup.scheduling-enabled=true","app.history-cleanup.scan-delay-ms=100"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class HistoryCleanupIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Test void automaticallyDeletesExpiredCompletedOperationsInBatchesAndKeepsLiveOrIncompleteRecords() {
        long user=Long.MAX_VALUE-100;
        String key=UUID.randomUUID().toString();
        Instant now=Instant.now();
        for(int i=0;i<520;i++) insert(user,key+i,now.minusSeconds(1),200);
        insert(user,key+"-live",now.plusSeconds(3600),200);
        insert(user,key+"-incomplete",now.minusSeconds(1),null);
        String expired=UUID.randomUUID().toString().replace("-","").repeat(2);
        String live=UUID.randomUUID().toString().replace("-","").repeat(2);
        jdbc.update("INSERT INTO revoked_token(token_hash,expires_at) VALUES (?,?),(?,?)",expired,Timestamp.from(now.minusSeconds(1)),live,Timestamp.from(now.plusSeconds(3600)));
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency WHERE user_id=? AND request_key LIKE ?",Integer.class,user,key+"%")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM revoked_token WHERE token_hash=?",Integer.class,expired)).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM revoked_token WHERE token_hash=?",Integer.class,live)).isEqualTo(1);
    }
    void insert(long user,String key,Instant expires,Integer status) {
        jdbc.update("INSERT INTO api_idempotency(user_id,operation,request_key,payload_hash,result_http_status,expires_at) VALUES (?,'CLEANUP_TEST',?,UNHEX(SHA2('fixture',256)),?,?)",user,key,status,Timestamp.from(expires));
    }
}
