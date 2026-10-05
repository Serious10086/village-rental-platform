package com.gjf.village.controller.portal;

import com.gjf.village.common.result.Result;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件上传接口。
 *
 * <p>需要登录才能上传 —— 否则任何人都能把服务器当免费图床用。
 */
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Tag(name = "文件上传", description = "图片上传")
public class FileController {

    private final FileService fileService;

    @PostMapping("/upload")
    @Operation(summary = "上传图片",
            description = "支持 jpg/png/gif/webp/bmp,单张最大 10MB。返回可访问的 URL")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        // 先确认已登录。上传接口没有加 @RequiresPerm,
        // 因为所有登录用户(村民、买家)都需要传图;只需要确认身份有效即可。
        UserContext.get();

        String url = fileService.uploadImage(file);

        Map<String, String> data = new HashMap<>();
        data.put("url", url);
        return Result.success("上传成功", data);
    }
}
