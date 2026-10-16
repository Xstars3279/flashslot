package com.flashslot.auth.service;

import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.web.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 操作人 ID 取自当前请求身份，取得业务锁后复核管理员当前状态。 */
@Component
@RequiredArgsConstructor
public class CurrentAdmin {
    private final AccountPermissionMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public long lockAndRequireAdmin() {
        Long userId = BaseContext.getCurrentId();
        return requireAdmin(mapper.lockBookingAccount(userId));
    }

    /** 查询入口参加只读快照，不使用 FOR UPDATE。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long requireAdmin() {
        return requireAdmin(mapper.findCurrentAccountById(BaseContext.getCurrentId()));
    }

    private long requireAdmin(AppUser user) {
        if (!BaseContext.hasRole("ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "需要管理员权限");
        }
        AccountValidator.requireEnabled(user, "账号不存在或权限已变更");
        if (!"ADMIN".equals(user.getRole()))
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号不是管理员");
        return user.getId();
    }
}
