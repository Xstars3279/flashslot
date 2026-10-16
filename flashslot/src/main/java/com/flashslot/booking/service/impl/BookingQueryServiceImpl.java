package com.flashslot.booking.service.impl;

import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.AccountValidator;
import com.flashslot.booking.entity.BookingOrder;
import com.flashslot.booking.mapper.BookingReadMapper;
import com.flashslot.booking.service.BookingQueryService;
import com.flashslot.booking.vo.*;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.web.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 查询不推进订单状态；账号、游标、订单与释放进度在同一个只读快照中读取。 */
@Service
public class BookingQueryServiceImpl implements BookingQueryService {
    @Autowired private AccountPermissionMapper accounts;
    @Autowired private BookingReadMapper bookings;

    /** 本人可查看所有历史状态；跨用户查看须同时满足认证权限和数据库当前 ADMIN 角色。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public BookingDetailView get(String orderNo) {
        AppUser account = requireReader();
        boolean administrator = "ADMIN".equals(account.getRole()) && BaseContext.hasRole("ADMIN");
        BookingDetailView view = bookings.findVisible(orderNo, account.getId(), administrator);
        if (view == null)
            throw new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "订单不存在");
        return view;
    }

    /** 只查询当前账号，管理员也没有列表越权；空游标从最新订单开始。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public BookingPage list(String cursor, int limit) {
        AppUser account = requireReader();
        if (limit < 1
                || limit > 100
                || (cursor != null && !cursor.matches("[A-Za-z0-9._:-]{0,64}"))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分页参数不合法");
        }
        BookingOrder anchor = null;
        if (cursor != null && !cursor.isEmpty()) {
            anchor = bookings.findOwnCursor(cursor, account.getId());
            if (anchor == null)
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页游标无效，请从第一页重新查询");
        }
        var found = bookings.findOwnPage(account.getId(), anchor, limit + 1);
        boolean more = found.size() > limit;
        var items = found.stream().limit(limit).toList();
        return new BookingPage(items, more ? items.get(items.size() - 1).getOrderNo() : null, more);
    }

    /** 每次读取重新核对正式账号和启用状态；预约处罚不影响查看已有订单。 */
    private AppUser requireReader() {
        AppUser account = accounts.findCurrentAccountById(BaseContext.getCurrentId());
        AccountValidator.requireEnabled(account);
        return AccountValidator.requireFormal(account, "当前账号不能访问正式订单");
    }
}
