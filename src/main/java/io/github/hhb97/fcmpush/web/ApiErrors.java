package io.github.hhb97.fcmpush.web;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 所有错误统一返回 {"message": "..."}，网页直接显示 message。 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().stream()
                .map(err -> err.getDefaultMessage()).filter(m -> m != null).findFirst().orElse("参数错误");
        return ResponseEntity.badRequest().body(Map.of("message", msg));
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, ConstraintViolationException.class })
    ResponseEntity<Map<String, String>> unreadable(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "请求格式错误"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "请求失败" : e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> other(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "服务器错误：" + e.getClass().getSimpleName()));
    }
}
