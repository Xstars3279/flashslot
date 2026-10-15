package com.flashslot.auth.service;

import com.flashslot.auth.dto.LoginRequest;
import com.flashslot.auth.vo.LoginView;

/** 登录业务接口。 */
public interface LoginService {
    /** 校验凭据并签发携带用户 ID 的 JWT。 */
    LoginView login(LoginRequest request);
}
