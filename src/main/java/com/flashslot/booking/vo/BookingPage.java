package com.flashslot.booking.vo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 订单号游标分页；末页 nextCursor 为 null，不执行全量 COUNT。 */
@Getter
@AllArgsConstructor
public class BookingPage {
    private List<BookingDetailView> items;
    private String nextCursor;
    private boolean hasMore;
}
