package com.hmdp.utils;

/**
 * 与 tb_voucher_order.status 对应，流转一律走条件更新（CAS），不做先查后改
 */
public class OrderStatus {

    public static final int UNPAID = 1;
    public static final int PAID = 2;
    public static final int USED = 3;
    public static final int CANCELED = 4;
    public static final int REFUNDING = 5;
    public static final int REFUNDED = 6;
}
