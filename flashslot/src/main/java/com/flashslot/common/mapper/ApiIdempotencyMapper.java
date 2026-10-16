package com.flashslot.common.mapper;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.flashslot.common.entity.ApiIdempotency;

import org.apache.ibatis.annotations.*;

import java.time.Instant;

/** 按用户、操作、请求键完整定位，记录与业务事务一起提交。 */
@Mapper
public interface ApiIdempotencyMapper {
    /** 新写操作使用固定服务端 operation 区分幂等作用域。 */
    @Insert(
            """
            INSERT INTO api_idempotency(user_id,operation,request_key,payload_hash,expires_at)
            VALUES (#{userId},#{operation},#{key},#{hash},#{expires})
            ON DUPLICATE KEY UPDATE request_key=request_key
            """)
    int acquireOperation(
            @Param("userId") long userId,
            @Param("operation") String operation,
            @Param("key") String key,
            @Param("hash") byte[] hash,
            @Param("expires") Instant expires);

    @Select(
            "SELECT * FROM api_idempotency WHERE user_id=#{userId} AND operation=#{operation} AND"
                    + " request_key=#{key} FOR UPDATE")
    @Results(
            @Result(
                    column = "result_json",
                    property = "resultJson",
                    typeHandler = JacksonTypeHandler.class))
    ApiIdempotency lockOperation(
            @Param("userId") long userId,
            @Param("operation") String operation,
            @Param("key") String key);

    @Update(
            """
UPDATE api_idempotency SET result_http_status=#{status},result_code=#{code},result_json=#{json}
WHERE user_id=#{userId} AND operation=#{operation} AND request_key=#{key}
""")
    int completeOperation(
            @Param("userId") long userId,
            @Param("operation") String operation,
            @Param("key") String key,
            @Param("status") int status,
            @Param("code") String code,
            @Param("json") String json);
}
