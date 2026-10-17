package com.flashslot.auth.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.nio.charset.StandardCharsets;

/** 密码请求不生成 toString；不进入幂等响应或审计内容。 */
public final class AccountRequests {
    @Getter @Setter
    public static class NewPassword {
        @NotBlank @Size(min=8,max=72)
        private String newPassword;
        @AssertTrue(message="密码不能超过 72 个 UTF-8 字节")
        public boolean isPasswordWithinBcryptLimit() { return within(newPassword); }
    }
    @Getter @Setter
    public static class ChangePassword extends NewPassword {
        @NotBlank @Size(max=72) private String currentPassword;
        @AssertTrue(message="原密码不能超过 72 个 UTF-8 字节")
        public boolean isCurrentPasswordWithinBcryptLimit() { return within(currentPassword); }
    }
    @Getter @Setter
    public static class ResetPassword extends NewPassword {
        @NotBlank @Size(max=256) private String reason;
    }
    @Getter @Setter
    public static class CreateAccount {
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.-]{2,63}") private String username;
        @NotBlank @Size(min=8,max=72) private String password;
        @NotNull @Pattern(regexp="USER|ADMIN") private String role;
        @NotBlank @Size(max=256) private String reason;
        @AssertTrue(message="密码不能超过 72 个 UTF-8 字节")
        public boolean isPasswordWithinBcryptLimit() { return within(password); }
    }
    public record AccountStatus(@NotNull Boolean enabled,@NotBlank @Size(max=256) String reason) {}
    private static boolean within(String password) { return password==null || password.getBytes(StandardCharsets.UTF_8).length<=72; }
}
