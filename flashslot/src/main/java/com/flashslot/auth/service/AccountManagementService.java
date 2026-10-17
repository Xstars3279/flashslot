package com.flashslot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.flashslot.auth.dto.AccountRequests.*;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.vo.ManagedAccountView;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.web.BusinessException;
import com.flashslot.operations.service.AdminAuditService;
import com.flashslot.operations.vo.RecoveryPage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountManagementService {
    private final AccountPermissionMapper accounts;
    private final CurrentAdmin admin;
    private final PasswordEncoder passwords;
    private final AdminAuditService audit;
    private final AccountBanCache bans;

    @Transactional(readOnly=true)
    public RecoveryPage<ManagedAccountView> list(long cursor,int limit,String username) {
        admin.requireAdmin();
        if(cursor<0 || limit<1 || limit>100 || (username!=null && username.length()>64))
            throw failure(HttpStatus.BAD_REQUEST,"分页或账号参数不合法");
        var query=new QueryWrapper<AppUser>().select("id","username","role","enabled").gt("id",cursor);
        if(username!=null && !username.isBlank()) query.eq("username",username.strip());
        var found=accounts.selectList(query.orderByAsc("id").last("LIMIT "+(limit+1)));
        var items=found.stream().limit(limit).map(ManagedAccountView::from).toList();
        boolean more=found.size()>limit;
        return new RecoveryPage<>(items,more?Long.toString(items.getLast().id()):null,more);
    }

    @Transactional
    public ManagedAccountView create(CreateAccount request) {
        long actor=admin.lockAndRequireAdmin();
        var user=new AppUser();
        user.setUsername(request.getUsername()); user.setPasswordHash(passwords.encode(request.getPassword()));
        user.setRole(request.getRole()); user.setEnabled(true); user.setCredentialVersion(0L);
        try { accounts.insert(user); }
        catch(DuplicateKeyException ex) { throw failure(HttpStatus.CONFLICT,"用户名已存在"); }
        audit.recordTarget(actor,"ACCOUNT_CREATE",Long.toString(user.getId()),request.getReason().strip());
        return ManagedAccountView.from(user);
    }

    @Transactional
    public void changePassword(ChangePassword request) {
        var user=AccountValidator.requireFormal(accounts.lockCredentials(BaseContext.getCurrentId()));
        if(!passwords.matches(request.getCurrentPassword(),user.getPasswordHash()))
            throw failure(HttpStatus.BAD_REQUEST,"原密码不正确");
        updatePassword(user.getId(),request.getNewPassword());
    }

    @Transactional
    public void reset(long target,ResetPassword request) {
        lockAccounts(target);
        updatePassword(target,request.getNewPassword());
        audit.recordTarget(BaseContext.getCurrentId(),"PASSWORD_RESET",Long.toString(target),request.getReason().strip());
    }

    @Transactional
    public ManagedAccountView status(long target,AccountStatus request) {
        var user=lockAccounts(target);
        if(target==BaseContext.getCurrentId() && !request.enabled())
            throw failure(HttpStatus.CONFLICT,"不能停用当前登录的管理员账号");
        if(!request.enabled().equals(user.getEnabled())) {
            // The account locks are already held; read the version under the same transaction.
            long nextVersion=accounts.lockCredentials(target).getCredentialVersion()+1;
            accounts.update(null,new UpdateWrapper<AppUser>().eq("id",target).set("enabled",request.enabled())
                .setSql("credential_version=credential_version+1"));
            user.setEnabled(request.enabled());
            bans.synchronizeAfterCommit(target,nextVersion,request.enabled());
            audit.recordTarget(BaseContext.getCurrentId(),"ACCOUNT_STATUS",Long.toString(target),request.reason().strip());
        }
        return ManagedAccountView.from(user);
    }

    private void updatePassword(long id,String value) {
        accounts.update(null,new UpdateWrapper<AppUser>().eq("id",id).set("password_hash",passwords.encode(value))
            .setSql("credential_version=credential_version+1"));
    }

    /** 与现有信用管理一样按账号 ID 升序取锁。 */
    private AppUser lockAccounts(long target) {
        long actor=BaseContext.getCurrentId();
        AppUser user;
        if(actor<target) { accounts.lockBookingAccount(actor); user=accounts.lockBookingAccount(target); }
        else { user=accounts.lockBookingAccount(target); if(actor!=target) accounts.lockBookingAccount(actor); }
        admin.lockAndRequireAdmin();
        if(user==null) throw failure(HttpStatus.NOT_FOUND,"账号不存在");
        return user;
    }
    private BusinessException failure(HttpStatus status,String message) { return new BusinessException(status,"ACCOUNT_MANAGEMENT",message); }
}
