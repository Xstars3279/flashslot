package com.flashslot.common.web;

import lombok.Data;

import java.io.Serializable;

/** 与苍穹外卖一致的统一业务响应：1 成功，0 失败；HTTP 协议状态单独处理。 */
@Data
public class Result<T> implements Serializable {
    private Integer code;
    private String msg;
    /** 稳定的机器可读错误码；成功响应为 null，code 仍保持 1/0 兼容。 */
    private String errorCode;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 1;
        result.data = data;
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(String msg) {
        return error("REQUEST_FAILED",msg);
    }

    public static <T> Result<T> error(String errorCode,String msg) {
        Result<T> result = new Result<>();
        result.code = 0;
        result.msg = msg;
        result.errorCode = errorCode;
        return result;
    }
}
