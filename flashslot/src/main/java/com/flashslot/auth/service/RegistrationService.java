package com.flashslot.auth.service;

import com.flashslot.auth.dto.*;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.vo.LoginView;
import com.flashslot.common.web.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service @RequiredArgsConstructor
public class RegistrationService {
    private final AccountPermissionMapper accounts;
    private final RegistrationCodeService codes;
    private final PasswordEncoder passwords;
    private final LoginService login;
    public String sendCode(String phone,String ip) {
        codes.requireEnabled(); requireUnused(phone); return codes.send(phone,ip);
    }
    @Transactional(rollbackFor=Exception.class)
    public LoginView register(RegistrationRequests.Register request) {
        String phone=request.getPhone();
        codes.requireEnabled(); requireUnused(phone);
        try { codes.verify(phone,request.getCode()); }
        catch(BusinessException ex) { requireUnused(phone); throw ex; }
        var user=new AppUser();
        user.setUsername(phone); user.setPhone(phone); user.setRole("USER"); user.setEnabled(true); user.setCredentialVersion(0L);
        user.setPasswordHash(passwords.encode(request.getPassword()));
        try { accounts.insert(user); }
        catch(DuplicateKeyException ex) { throw duplicate(); }
        var credentials=new LoginRequest(); credentials.setUsername(phone); credentials.setPassword(request.getPassword());
        LoginView result=login.login(credentials);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { codes.consume(phone,request.getCode()); }
        });
        return result;
    }
    private void requireUnused(String phone) { if(accounts.registrationExists(phone)>0) throw duplicate(); }
    private BusinessException duplicate() { return new BusinessException(HttpStatus.CONFLICT,"ACCOUNT_EXISTS","手机号或账号已注册，请直接登录"); }
}
