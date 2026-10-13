package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 头像更新请求。
 *
 * <p>
 * 前端将图片编码为 Base64 字符串后传入，后端直接存储至数据库。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class ChangeAvatarReq {

    /** Base64 编码的头像数据（含 data:image/xxx;base64, 前缀） */
    @NotBlank(message = "头像数据不能为空")
    @Size(max = 2_800_000, message = "头像大小不能超过 2 MB")
    @Pattern(
            regexp = "^data:image/(?:png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$",
            message = "头像仅支持 PNG、JPEG 或 WebP Base64 图片")
    private String avatar;
}
