package com.flashslot.booking.mapper;

import com.flashslot.booking.entity.BookingOrder;
import com.flashslot.booking.vo.BookingDetailView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 订单只读查询，完整 SQL 位于 mapper/booking/BookingReadMapper.xml。 */
@Mapper
public interface BookingReadMapper {
    /** 按订单号读取，归属/管理员条件直接作用于 SQL；其他人的订单等同不存在。 */
    BookingDetailView findVisible(@Param("orderNo") String orderNo,@Param("userId") long userId,
        @Param("administrator") boolean administrator);

    /** 翻页锚点只能来自当前用户；读取不可变的创建时间和内部 ID，不返回实体给客户端。 */
    BookingOrder findOwnCursor(@Param("orderNo") String orderNo,@Param("userId") long userId);

    /**
     * 按 (created_at,id) 倒序读取，匹配 idx_order_user_list；同毫秒订单用 ID 确定顺序。
     * 查询 limit+1 条判断末页，游标由已授权订单定位，所有条件采用绑定参数。
     */
    List<BookingDetailView> findOwnPage(@Param("userId") long userId,@Param("anchor") BookingOrder anchor,
        @Param("fetchSize") int fetchSize);
}
