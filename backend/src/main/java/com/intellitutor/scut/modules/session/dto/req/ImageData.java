package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片数据（base64 URL 形式），用于 explanation 和 discussion 的请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageData {

    /** 图片文件名 */
    private String name;

    /**
     * base64 编码的图片 URL（或外链 URL）。
     * Python Pydantic 字段名为 url（小写），与 Java 一致，无需 @JsonProperty。
     */
    private String url;
}
