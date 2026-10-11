package com.vulnscope.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> badRequest(IllegalArgumentException error) {
        return ApiResponse.error(400, error.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> conflict() {
        return ApiResponse.error(409, "数据已存在");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst().map(item -> item.getDefaultMessage()).orElse("请求参数错误");
        return ApiResponse.error(400, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> serverError(Exception error) {
        return ApiResponse.error(500, "服务器处理失败：" + error.getMessage());
    }
}
