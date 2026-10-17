package com.flashslot.common.mapper;
import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface HistoryCleanupMapper {
    @Delete("DELETE FROM api_idempotency WHERE expires_at<=#{now} AND result_http_status IS NOT NULL ORDER BY expires_at LIMIT 500")
    int completedOperations(Instant now);
    @Delete("DELETE FROM revoked_token WHERE expires_at<=#{now} ORDER BY expires_at LIMIT 500")
    int revokedTokens(Instant now);
    @Delete("DELETE FROM outbox_event WHERE status='ARCHIVED' AND created_at<#{before} ORDER BY created_at,event_id LIMIT 500")
    int archivedEvents(Instant before);
}
