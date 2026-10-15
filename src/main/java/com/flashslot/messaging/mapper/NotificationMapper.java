package com.flashslot.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashslot.messaging.dto.NotificationQueryRequest;
import com.flashslot.messaging.entity.Notification;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
    List<Notification> findOwnPage(
            @Param("userId") long userId,
            @Param("request") NotificationQueryRequest request,
            @Param("anchor") Notification anchor,
            @Param("fetchSize") int fetchSize);

    @Update(
            "UPDATE notification SET read_at=#{now} WHERE id=#{id} AND user_id=#{userId} AND"
                + " read_at IS NULL")
    int markRead(@Param("id") long id, @Param("userId") long userId, @Param("now") Instant now);
}
