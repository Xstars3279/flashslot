package com.flashslot.messaging.service;

import com.flashslot.messaging.dto.NotificationQueryRequest;
import com.flashslot.messaging.vo.*;

public interface NotificationService {
    NotificationPage list(NotificationQueryRequest request);

    NotificationReadView markRead(long id);
}
