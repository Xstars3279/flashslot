package com.flashslot.auth.dto;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import lombok.Getter;
import lombok.Setter;

/** JSON 登录请求；不提供 toString，避免日志意外输出密码。 */
@Getter
@Setter
public class LoginRequest {
    @NotBlank @Size(max=64)
    private String username;
    @NotBlank @Size(max=72)
    private String password;

    /** BCrypt 输入最多 72 字节，多字节字符必须按 UTF-8 长度检查。 */
    @AssertTrue(message="密码不能超过 72 个 UTF-8 字节")
    public boolean isPasswordWithinBcryptLimit() {
        return password==null || password.getBytes(StandardCharsets.UTF_8).length<=72;
    }
}
