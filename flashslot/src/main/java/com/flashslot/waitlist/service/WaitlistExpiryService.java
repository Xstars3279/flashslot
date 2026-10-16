package com.flashslot.waitlist.service;

public interface WaitlistExpiryService {
    void scanExpired();
    void expireOne(long id);
}
