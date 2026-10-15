package com.flashslot.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.AccountValidator;
import com.flashslot.booking.dto.CancelBookingRequest;
import com.flashslot.booking.entity.BookingOrder;
import com.flashslot.booking.mapper.*;
import com.flashslot.booking.service.BookingCancellationService;
import com.flashslot.booking.service.BookingLifecycleService;
import com.flashslot.booking.vo.BookingDetailView;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.service.IdempotentOperationService;
import com.flashslot.common.web.*;
import com.flashslot.operations.service.AdminAuditService;
import com.flashslot.resource.mapper.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.LongStream;

/** 取消、提前结束和迟到状态归一化，所有写入与幂等结果同事务。 */
@Service
public class BookingCancellationServiceImpl implements BookingCancellationService {
    @Autowired private IdempotentOperationService operations;
    @Autowired private BookingLifecycleMapper bookingMapper;
    @Autowired private BookingReadMapper reads;
    @Autowired private ResourceMapper resourceMapper;
    @Autowired private ResourceDayMapper dayMapper;
    @Autowired private AccountPermissionMapper accountMapper;
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private AdminAuditService audit;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CommittedBusinessException.class)
    public BookingDetailView cancel(String orderNo, String key, CancelBookingRequest request) {
        long userId = BaseContext.getCurrentId();
        String reason = request.getReason() == null ? "" : request.getReason().strip();
        var record = operations.prepare(userId, "CANCEL_BOOKING", key, orderNo + "\n" + reason);
        if (record.getResultHttpStatus() != null) {
            var actor = AccountValidator.requireFormal(accountMapper.lockBookingAccount(userId));
            authorizeReplay(orderNo, actor);
            return operations.replay(record, BookingDetailView.class);
        }
        try {
            BookingDetailView view = cancelLocked(userId, orderNo, reason);
            operations.saveSuccess(record, 200, view);
            return view;
        } catch (CommittedBusinessException ex) {
            operations.saveRejection(record, ex);
            throw ex;
        }
    }

    /** 回放只读检查，不在已持有用户锁时逆序获取资源锁；订单归属不会被业务修改。 */
    private void authorizeReplay(String orderNo, AppUser actor) {
        BookingOrder order = bookingMapper.locate(orderNo);
        if (order == null
                || (!order.getUserId().equals(actor.getId())
                        && (!BaseContext.hasRole("ADMIN") || !"ADMIN".equals(actor.getRole()))))
            throw failure(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "订单不存在");
    }

    /** 资源共享 → 日历 → 订单 → 涉及账号按 ID 升序；停止开放不阻止取消。 */
    private BookingDetailView cancelLocked(long actorId, String orderNo, String reason) {
        BookingOrder located = bookingMapper.locate(orderNo);
        if (located == null) {
            AccountValidator.requireFormal(accountMapper.lockBookingAccount(actorId));
            throw new CommittedBusinessException(
                    failure(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "订单不存在"));
        }
        resourceMapper.lockShared(located.getResourceId());
        var day = dayMapper.lockDay(located.getResourceId(), located.getBookDate());
        BookingOrder order = bookingMapper.lock(located.getId());
        Map<Long, AppUser> accounts = new HashMap<>();
        LongStream.of(actorId, order.getUserId())
                .distinct()
                .sorted()
                .forEach(id -> accounts.put(id, accountMapper.lockBookingAccount(id)));
        AppUser actor = accounts.get(actorId), owner = accounts.get(order.getUserId());
        boolean administrator = actorId != order.getUserId();
        try {
            AccountValidator.requireFormal(actor);
            if (administrator
                    && (!BaseContext.hasRole("ADMIN") || !"ADMIN".equals(actor.getRole())))
                throw failure(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "订单不存在");
            if (administrator && reason.isBlank())
                throw failure(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "管理员取消他人订单需要填写原因");
        } catch (BusinessException ex) {
            throw new CommittedBusinessException(ex);
        }
        Instant decisionTime = clock.instant();
        if (Set.of("CANCELED", "NO_SHOW", "COMPLETED").contains(order.getStatus()))
            return result(order, actorId);

        lifecycle.terminateLocked(
                order, owner, day.getRevision(), decisionTime, administrator, reason);
        if (administrator) audit.recordTarget(actorId, "BOOKING_CANCEL", orderNo, reason);
        return result(order, actorId);
    }

    private BookingDetailView result(BookingOrder order, long actorId) {
        return reads.findVisible(order.getOrderNo(), actorId, true);
    }

    private BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
