package com.flashslot.common.web;

import com.flashslot.auth.service.LoginRateLimitException;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(LoginRateLimitException.class)
    public Result<Void> limited(LoginRateLimitException ex, HttpServletResponse response) {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(ex.getRetryAfter()));
        return Result.error(ex.getMessage());
    }

    /** 事务代理已完成回滚；前端使用原幂等键重新提交，不能把锁冲突视为成功。 */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> busy(org.springframework.dao.PessimisticLockingFailureException ex) {
        return Result.error("操作繁忙，请使用原请求键重试");
    }

    @ExceptionHandler(BusinessException.class)
    public Result<Void> business(BusinessException ex, HttpServletResponse response) {
        response.setStatus(ex.getStatus().value());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        ServletRequestBindingException.class,
        HandlerMethodValidationException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> invalid(Exception ex) {
        return Result.error("请求参数不合法，请检查字段、格式和范围");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> unexpected(Exception ex, HttpServletResponse response) {
        // 保留 Spring 的 404、405、415 等协议错误状态，响应体使用统一格式。
        if (ex instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            int status = error.getStatusCode().value();
            response.setStatus(status);
            return Result.error("请求地址、方法或内容格式不受支持");
        }
        log.error("处理请求时发生未预期异常", ex);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return Result.error("服务暂时不可用，请稍后重试");
    }
}
