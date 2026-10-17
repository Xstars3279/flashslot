package com.flashslot.messaging.mapper;

import com.flashslot.common.persistence.UtcInstantTypeHandler;
import com.flashslot.messaging.metrics.MessageQueueSnapshot;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MessageMetricsMapper {
    @Select("""
        <script>
        SELECT o.*,
          (SELECT COUNT(*) FROM dead_letter WHERE status='FAILED') AS dead_letter_failed,
          (SELECT COUNT(*) FROM release_job WHERE status='PENDING') AS release_pending,
          (SELECT MIN(next_run_at) FROM release_job WHERE status='PENDING') AS oldest_release_due,
          (SELECT COUNT(*) FROM release_job WHERE status='MANUAL_REVIEW') AS release_manual_review
        FROM (
          SELECT
            COALESCE(SUM(CASE WHEN status='NEW' AND event_type IN
              <foreach collection="types" item="type" open="(" separator="," close=")">#{type}</foreach>
              THEN 1 ELSE 0 END),0) AS outbox_new,
            COALESCE(SUM(CASE WHEN status='SENDING' AND event_type IN
              <foreach collection="types" item="type" open="(" separator="," close=")">#{type}</foreach>
              THEN 1 ELSE 0 END),0) AS outbox_sending,
            COALESCE(SUM(status='FAILED'),0) AS outbox_failed,
            COALESCE(SUM(status='ARCHIVED'),0) AS outbox_archived,
            MIN(CASE WHEN status IN ('NEW','SENDING') AND event_type IN
              <foreach collection="types" item="type" open="(" separator="," close=")">#{type}</foreach>
              THEN created_at ELSE NULL END) AS oldest_pending
          FROM outbox_event WHERE status IN ('NEW','SENDING','FAILED','ARCHIVED')
        ) o
        </script>
        """)
    @Results({
        @Result(column="oldest_pending", property="oldestPending", typeHandler=UtcInstantTypeHandler.class),
        @Result(column="oldest_release_due", property="oldestReleaseDue", typeHandler=UtcInstantTypeHandler.class)
    })
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    MessageQueueSnapshot snapshot(@Param("types") List<String> types);
}
