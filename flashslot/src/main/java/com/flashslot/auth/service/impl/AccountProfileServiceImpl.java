package com.flashslot.auth.service.impl;

import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.AccountProfileService;
import com.flashslot.auth.service.AccountValidator;
import com.flashslot.auth.vo.AccountProfileView;
import com.flashslot.common.context.BaseContext;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountProfileServiceImpl implements AccountProfileService {
    private final AccountPermissionMapper accounts;
    private final Clock clock;

    @Override
    public AccountProfileView getCurrent() {
        var account = AccountValidator.requireFormal(accounts.findProfileById(BaseContext.getCurrentId()));
        var now = clock.instant();
        return new AccountProfileView(account.getId(), account.getUsername(), account.getRole(),
            account.getCreditScore(), account.getRestrictionUntil(),
            account.getRestrictionUntil() != null && now.isBefore(account.getRestrictionUntil()), now);
    }
}
