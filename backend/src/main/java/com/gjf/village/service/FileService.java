package com.gjf.village.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传服务。
 */
public interface FileService {

    /**
     * 保存上传的图片。
     *
     * @return 可对外访问的 URL,如 {@code /upload/2026/09/17/3f2a....jpg}
     */
    String uploadImage(MultipartFile file);
}
