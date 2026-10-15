package com.flashslot.operations.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.CurrentAdmin;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.service.IdempotentOperationService;
import com.flashslot.common.web.BusinessException;
import com.flashslot.credit.entity.CreditLedger;
import com.flashslot.credit.mapper.CreditLedgerMapper;
import com.flashslot.operations.dto.*;
import com.flashslot.operations.service.*;
import com.flashslot.operations.vo.AccountRecoveryView;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountRecoveryServiceImpl implements AccountRecoveryService {
    @Autowired private AccountPermissionMapper accounts;
    @Autowired private CurrentAdmin admin;
    @Autowired private IdempotentOperationService operations;
    @Autowired private CreditLedgerMapper ledger;
    @Autowired private AdminAuditService audit;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountRecoveryView adjustCredit(
            long userId, String key, CreditAdjustmentRequest request) {
        long actor = BaseContext.getCurrentId();
        String reason = request.getReason().strip();
        var operation =
                operations.prepare(
                        actor,
                        "CREDIT_ADJUST",
                        key,
                        userId + "\n" + request.getDelta() + "\n" + reason);
        AppUser user = lockAccounts(actor, userId);
        if (operation.getResultHttpStatus() != null)
            return operations.replay(operation, AccountRecoveryView.class);
        int next = user.getCreditScore() + request.getDelta();
        if (request.getDelta() == 0 || next < 0 || next > 100)
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "INVALID_SCORE", "信用分须在0至100之间且调整值不能为0");
        if (accounts.update(
                        null,
                        new UpdateWrapper<AppUser>().eq("id", userId).set("credit_score", next))
                != 1) throw new IllegalStateException("信用分更新失败");
        CreditLedger entry = new CreditLedger();
        entry.setUserId(userId);
        entry.setEventType("ADMIN_ADJUST");
        entry.setBusinessId(UUID.randomUUID().toString());
        entry.setDelta(request.getDelta());
        entry.setScoreBefore(user.getCreditScore());
        entry.setScoreAfter(next);
        entry.setReason(reason);
        if (ledger.insert(entry) != 1) throw new IllegalStateException("信用流水保存失败");
        audit.recordTarget(actor, "CREDIT_ADJUST", Long.toString(userId), reason);
        var result = new AccountRecoveryView(userId, next, user.getRestrictionUntil());
        operations.saveSuccess(operation, 200, result);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountRecoveryView clearRestriction(long userId, String key, RecoveryRequest request) {
        long actor = BaseContext.getCurrentId();
        String reason = request.getReason().strip();
        var operation = operations.prepare(actor, "RESTRICTION_CLEAR", key, userId + "\n" + reason);
        AppUser user = lockAccounts(actor, userId);
        if (operation.getResultHttpStatus() != null)
            return operations.replay(operation, AccountRecoveryView.class);
        if (accounts.update(
                        null,
                        new UpdateWrapper<AppUser>()
                                .eq("id", userId)
                                .set("restriction_until", null))
                != 1) throw new IllegalStateException("限制解除失败");
        audit.recordTarget(actor, "RESTRICTION_CLEAR", Long.toString(userId), reason);
        var result = new AccountRecoveryView(userId, user.getCreditScore(), null);
        operations.saveSuccess(operation, 200, result);
        return result;
    }

    /** 与取消预约一致，多个账号按ID升序加锁，防止管理员交叉操作死锁。 */
    private AppUser lockAccounts(long actor, long target) {
        AppUser user;
        if (actor < target) {
            accounts.lockBookingAccount(actor);
            user = accounts.lockBookingAccount(target);
        } else {
            user = accounts.lockBookingAccount(target);
            if (actor != target) accounts.lockBookingAccount(actor);
        }
        admin.lockAndRequireAdmin();
        if (user == null)
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        return user;
    }
}
