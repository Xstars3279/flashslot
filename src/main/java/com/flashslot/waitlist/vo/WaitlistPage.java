package com.flashslot.waitlist.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class WaitlistPage {
    private List<WaitlistView> items;
    private String nextCursor;
    private boolean hasMore;
}
