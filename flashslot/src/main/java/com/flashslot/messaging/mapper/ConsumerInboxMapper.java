package com.flashslot.messaging.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ConsumerInboxMapper {
    @Select("SELECT COUNT(*) FROM consumer_inbox WHERE consumer_name='release-v1' AND event_id=#{eventId}")
    int releaseCompleted(String eventId);

    @Insert("INSERT INTO consumer_inbox(consumer_name,event_id) VALUES ('release-v1',#{eventId}) ON DUPLICATE KEY UPDATE event_id=event_id")
    int completeRelease(String eventId);

    @Select(
            "SELECT COUNT(*) FROM consumer_inbox WHERE consumer_name='notification-v1' AND"
                + " event_id=#{eventId}")
    int completed(String eventId);

    @Insert(
            "INSERT INTO consumer_inbox(consumer_name,event_id) VALUES"
                + " ('notification-v1',#{eventId})")
    int complete(String eventId);
}
