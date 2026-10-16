package com.flashslot.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 登录时的本人基础身份，权限是否仍有效由业务请求重新核对。 */
@Getter
@AllArgsConstructor
public class LoginUserView {
    private String username;
    private String role;
}
