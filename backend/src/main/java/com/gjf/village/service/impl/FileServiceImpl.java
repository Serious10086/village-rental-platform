package com.gjf.village.service.impl;

import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传服务实现。
 *
 * <p><b>四条安全措施,缺一不可:</b>
 * <ol>
 *   <li><b>不使用客户端传来的文件名</b> —— 服务端用 UUID 重新生成。
 *       客户端的文件名可能包含 {@code ../../} 做路径穿越,或者包含
 *       {@code .jsp/.sh} 之类的可执行后缀。用它就等于把服务器交给攻击者。</li>
 *   <li><b>扩展名白名单</b>而不是黑名单 —— 黑名单永远列不全,
 *       而白名单默认拒绝一切未列出的类型。</li>
 *   <li><b>按日期分目录</b> —— 单目录下堆几万个文件会让文件系统变慢,
 *       也给运维排查添麻烦。</li>
 *   <li><b>限制大小</b> —— 在 application.yml 的 multipart 配置里,
 *       Spring 会在读入内存前就拒绝超大文件,不会先占满内存再报错。</li>
 * </ol>
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    /** 允许的图片扩展名(白名单) */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 允许的 MIME 类型(第二道校验,防止改扩展名绕过) */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp");

    private static final DateTimeFormatter DATE_DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Value("${app.upload.path}")
    private String uploadPath;

    @Value("${app.upload.url-prefix:/upload}")
    private String urlPrefix;

    @Override
    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择要上传的图片");
        }

        // ── 校验 1:扩展名白名单 ──────────────────────────────
        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String extension = extractExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw BusinessException.badRequest(
                    "不支持的图片格式:" + extension + ",仅支持 " + ALLOWED_EXTENSIONS);
        }

        // ── 校验 2:MIME 类型 ──────────────────────────────────
        // 光看扩展名不够 —— 把 shell.jsp 改名成 shell.jpg 扩展名就通过了。
        // 不过 Content-Type 是客户端给的,同样可以伪造,所以它是"第二道"而不是"唯一"的防线。
        // 真正的最后一道防线是:上传目录不做脚本解析、不放在可执行路径下。
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw BusinessException.badRequest("文件类型不合法,请上传图片文件");
        }

        // ── 生成服务端文件名 ──────────────────────────────────
        // 完全抛弃原始文件名,只保留扩展名
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;

        // 按日期分目录:2026/09/17/
        String datePath = LocalDate.now().format(DATE_DIR_FMT);
        Path targetDir = Paths.get(uploadPath, datePath);

        try {
            Files.createDirectories(targetDir);

            // 归一化后再次确认目标路径确实在允许的目录内。
            // 这一步是防路径穿越的兜底:万一 datePath 或 fileName 将来被改成
            // 可拼接的值,resolve 出目录之外也会在这里被拦下。
            Path targetFile = targetDir.resolve(fileName).normalize();
            if (!targetFile.startsWith(Paths.get(uploadPath).normalize())) {
                log.error("检测到疑似路径穿越: target={}, uploadRoot={}", targetFile, uploadPath);
                throw BusinessException.badRequest("文件路径不合法");
            }

            file.transferTo(targetFile.toFile());
            log.info("图片上传成功: {}", targetFile);

            return urlPrefix + "/" + datePath + "/" + fileName;

        } catch (IOException e) {
            log.error("图片保存失败, targetDir={}", targetDir, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "图片上传失败,请稍后重试");
        }
    }

    /**
     * 提取扩展名(小写)。
     */
    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
