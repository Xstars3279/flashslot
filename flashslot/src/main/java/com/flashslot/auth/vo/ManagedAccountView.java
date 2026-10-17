package com.flashslot.auth.vo;

import com.flashslot.auth.entity.AppUser;

public record ManagedAccountView(long id,String username,String role,boolean enabled) {
    public static ManagedAccountView from(AppUser user) { return new ManagedAccountView(user.getId(),user.getUsername(),user.getRole(),Boolean.TRUE.equals(user.getEnabled())); }
}
