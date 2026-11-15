package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常处理器：把 Controller 抛出的异常统一转换为 Result 返回。 */
// ==================== 全局异常兜底 ====================
// @RestControllerAdvice = @ControllerAdvice + @ResponseBody：
// 拦截所有 Controller 的异常，返回值直接作为响应体（JSON）。
// 当前只兜底 RuntimeException：记录 error 级日志（含完整堆栈），
// 并向前端返回固定的"服务器异常"文案，不泄露内部信息。
// 业务性失败（如库存不足、店铺不存在）应由各接口直接返回
// Result.fail，而不是抛异常走到这里。
// 文件上传等场景抛出的 RuntimeException 会被本类捕获并统一响应。
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /** 兜底处理未捕获的运行时异常，返回统一失败 Result。 */
    // 记录完整堆栈便于排查，对外只返回固定文案"服务器异常"，
    // 避免把异常类名/消息直接暴露给前端。
    // @param e 捕获到的 RuntimeException
    // @return 统一失败响应 Result.fail("服务器异常")
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        // error 级别记录异常信息与完整堆栈
        log.error(e.toString(), e);
        // 对外统一文案，不回传 e.getMessage()
        return Result.fail("服务器异常");
    }
}
