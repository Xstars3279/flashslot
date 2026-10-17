package com.flashslot.auth.dto;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import lombok.Getter;
import lombok.Setter;

/** 不生成包含密码、验证码的 toString。 */
public final class RegistrationRequests {
    @Getter @Setter
    public static class Phone {
        @NotBlank @Pattern(regexp="1[3-9][0-9]{9}") private String phone;
    }
    @Getter @Setter
    public static class Register extends Phone {
        @NotBlank @Pattern(regexp="[0-9]{6}") private String code;
        @NotBlank @Size(min=8,max=72) private String password;
        @AssertTrue(message="密码不能超过72个UTF-8字节")
        public boolean isPasswordWithinBcryptLimit() {
            return password==null || password.getBytes(StandardCharsets.UTF_8).length<=72;
        }
    }
}
