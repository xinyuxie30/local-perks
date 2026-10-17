package com.hmdp.dto;

import lombok.Data;

/**
 * 资料编辑请求体：昵称 + 个人详情（介绍/性别/城市/生日）。
 * birthday 用字符串接收，服务端做 yyyy-MM-dd 校验，避免反序列化异常直接 500。
 */
@Data
public class ProfileDTO {

    private String nickName;

    private String introduce;

    /**
     * 性别：0 男，1 女；null 表示不改
     */
    private Boolean gender;

    private String city;

    /**
     * yyyy-MM-dd
     */
    private String birthday;
}
