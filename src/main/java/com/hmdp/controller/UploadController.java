package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** 图片上传控制器：探店笔记图片的本地上传与删除（路由前缀 /upload）。 */
// ==================== 图片上传业务域 ====================
// 文件保存到 SystemConstants.IMAGE_UPLOAD_DIR 指定的本机目录；
// 文件名用 UUID 重写（避免重名与中文路径问题），并按 hash 拆两级
// 子目录分散存储，避免单目录文件过多。
// 注意：当前存本机磁盘，生产环境应替换为 OSS 等对象存储；
// deleteBlogImg 是 GET 方式的删除接口（历史设计），仅限删除
// 上传目录内的文件。
// 上传与删除接口都需要登录（/upload/** 不在登录白名单中）。
// 文件名由服务端重新生成，不信任客户端原始文件名。
// 返回的相对文件名会作为 Blog 的 images 字段内容持久化。
// 图片删除常用于前端"预览后取消"场景，回收磁盘空间。
@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    /** 上传探店笔记图片：POST /upload/blog（multipart/form-data，字段名 file）。 */
    // 业务语义：createNewFileName 生成"两级子目录 + UUID 新文件名"
    // 后用 transferTo 直接落盘；返回相对文件名，前端拼接静态资源
    // 前缀访问。IO 失败抛 RuntimeException，交由全局异常处理器兜底。
    // @param image 上传的图片文件
    // @return 服务器上的相对文件名（如 /blogs/3/5/xxx.png）
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // getOriginalFilename 可能含路径信息，这里仅用于提取后缀
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            // transferTo 直接写入目标 File；父目录由 createNewFileName 创建
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 返回结果
            // 返回相对路径，前端拼接静态资源前缀访问
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            // IO 异常包装为 RuntimeException，由 WebExceptionAdvice 统一兜底
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /** 删除已上传的笔记图片：GET /blog/delete?name=文件名。 */
    // 注意：这是 GET 方式的删除接口（历史设计），仅删除
    // IMAGE_UPLOAD_DIR 下的指定文件；
    // 传入目录名时直接判失败，防止误删整棵目录树。
    // @param filename 相对文件名
    // @return 操作结果
    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        // 按相对路径定位文件，IMAGE_UPLOAD_DIR 为固定根目录
        // 目录名（isDirectory 为真）直接拒绝，避免 FileUtil.del 删除整个目录
        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        // FileUtil.del 删除文件，路径固定在根目录内，无越权风险
        FileUtil.del(file);
        // 删除成功返回 ok，无数据体
        // 文件不存在时 Hutool 的 del 静默跳过，不抛异常
        return Result.ok();
    }

    /** 生成新的存储文件名：保留后缀，主体换成 UUID 并拆两级子目录。 */
    // 规则：取 UUID 的 hash 低 8 位拆出两个 0~15 的数字 d1/d2
    // 作为两级子目录，把文件最多打散到 256 个目录，均匀分摊磁盘文件数。
    // @param originalFilename 原始文件名
    // @return 形如 /blogs/{d1}/{d2}/{uuid}.{suffix} 的相对路径
    private String createNewFileName(String originalFilename) {
        // 获取后缀
        // subAfter 取最后一个"."之后的内容作为后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        // hash 低 4 位与 4~7 位分别作为一、二级目录编号
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        // 目录不存在时递归创建（mkdirs 支持多级）
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        // StrUtil.format 用 {} 占位符拼接路径；文件名含目录前缀
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
