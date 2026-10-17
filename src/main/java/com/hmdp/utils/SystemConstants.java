package com.hmdp.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = System.getProperty("hmdp.image-upload-dir", System.getProperty("user.dir") + "/nginx-1.18.0/html/hmdp/imgs/");
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
    /**
     * 允许的最大页码，防止 (current-1)*size 整型溢出，也避免恶意深分页
     */
    public static final int MAX_PAGE_NUMBER = 1000;
}
