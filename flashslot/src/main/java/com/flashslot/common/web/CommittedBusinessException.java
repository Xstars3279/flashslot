package com.flashslot.common.web;

/** 已保存拒绝快照，或已完成到期状态转换的业务拒绝。 仅在这些明确的提交边界使用；其他异常仍按正常事务规则回滚。 */
public class CommittedBusinessException extends BusinessException {
    public CommittedBusinessException(BusinessException cause) {
        super(cause.getStatus(), cause.getCode(), cause.getMessage());
    }
}
