package com.flashslot.waitlist.service;

import com.flashslot.waitlist.dto.CreateWaitlistRequest;
import com.flashslot.waitlist.vo.WaitlistPage;
import com.flashslot.waitlist.vo.WaitlistView;

public interface WaitlistService {
    /** 已占用或 HANDOFF 区间申请候补，不预占用户时间格。 */
    WaitlistView create(String key, CreateWaitlistRequest request);

    /** 当前用户的候补与近似队列位置。 */
    WaitlistPage list(String cursor, int limit);

    WaitlistView cancel(String number, String key);
}
