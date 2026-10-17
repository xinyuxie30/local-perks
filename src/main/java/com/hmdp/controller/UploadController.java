package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.annotation.AdminOnly;
import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitDimension;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;
    private static final Set<String> ALLOWED_SUFFIXES =
            new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif", "webp"));
    /**
     * 只允许删除本服务生成的文件名，杜绝 ../../ 之类的路径穿越
     */
    private static final Pattern GENERATED_NAME = Pattern.compile(
            "^/blogs/[0-9a-f]{1,2}/[0-9a-f]{1,2}/[0-9a-fA-F-]{36}\\.(jpg|jpeg|png|gif|webp)$");

    @PostMapping("blog")
    @RateLimit(prefix = "upload:blog", limit = 10, windowSeconds = 60,
            dimension = RateLimitDimension.USER, message = "上传太频繁了")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        // 上传路径不经过登录拦截器（旧前端 el-upload 不带 token），这里显式要求登录态，
        // 否则匿名用户也能往服务器磁盘写文件
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        if (image == null || image.isEmpty()) {
            return Result.fail("文件不能为空");
        }
        if (image.getSize() > MAX_FILE_SIZE) {
            return Result.fail("图片不能超过 5MB");
        }
        String originalFilename = image.getOriginalFilename();
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        if (StrUtil.isBlank(suffix)) {
            return Result.fail("文件缺少扩展名");
        }
        suffix = suffix.toLowerCase(Locale.ROOT);
        if (!ALLOWED_SUFFIXES.contains(suffix)) {
            return Result.fail("只支持 jpg/jpeg/png/gif/webp 图片");
        }
        try {
            String fileName = createNewFileName(suffix);
            File target = resolveUnderRoot(fileName);
            if (target == null) {
                return Result.fail("文件名称不合法");
            }
            image.transferTo(target);
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 删除图片属于管理操作：原实现任何登录用户都能删任意图片，存在越权风险
     */
    @AdminOnly
    @DeleteMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        if (StrUtil.isBlank(filename) || !GENERATED_NAME.matcher(filename).matches()) {
            return Result.fail("错误的文件名称");
        }
        File file = resolveUnderRoot(filename);
        if (file == null || file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        if (file.exists() && !FileUtil.del(file)) {
            return Result.fail("删除失败");
        }
        return Result.ok();
    }

    /**
     * 保留 GET 兼容旧前端，但同样要求管理员
     */
    @AdminOnly
    @GetMapping("/blog/delete")
    public Result deleteBlogImgLegacy(@RequestParam("name") String filename) {
        return deleteBlogImg(filename);
    }

    private String createNewFileName(String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("创建上传目录失败");
        }
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    /**
     * 解析并校验最终路径必须落在上传根目录内
     */
    private File resolveUnderRoot(String relativeName) {
        try {
            File root = new File(SystemConstants.IMAGE_UPLOAD_DIR).getCanonicalFile();
            File target = new File(root, relativeName).getCanonicalFile();
            return target.toPath().startsWith(root.toPath()) ? target : null;
        } catch (IOException e) {
            log.warn("解析上传路径失败：{}", relativeName, e);
            return null;
        }
    }
}
