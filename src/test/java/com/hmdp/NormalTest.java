// 包声明：普通学习性测试
package com.hmdp;

// Test：测试方法注解
import org.junit.jupiter.api.Test;

// 临时验证类：位运算两种写法的签到统计对比
public class NormalTest {

// 临时验证用例：对比两种位运算统计连续签到天数的写法
    @Test
// testBitMap 方法体
    void testBitMap() {
// 用二进制字面量模拟签到位图（1=已签到，最后一个 0 是今天未签）
        int i = 0b1110111111111111111111111;

// 记法一起始时间
        long t1 = System.nanoTime();
// 连续签到计数器
        int count = 0;
// 写法一：按位与判断最低位
        while (true){
// 最低位为 0 表示今天未签到
            if ((i & 1) == 0){
// 提前结束
                    break;
// else 分支
            }else{
// 已签到，计数 +1
                count++;
// if-else 结束
            }
// 无符号右移一位，继续看下一天
            i >>>= 1;
// 写法一循环结束
        }
// 记法一结束时间
        long t2 = System.nanoTime();
// 输出写法一耗时
        System.out.println("time1 = " + (t2 - t1));
// 输出写法一统计出的连续签到天数
        System.out.println("count = " + count);

// 重新赋值位图，准备测第二种写法
        i = 0b1110111111111111111111111;
// 记法二起始时间
        long t3 = System.nanoTime();
// 第二个计数器
        int count2 = 0;
// 写法二：右移再左移还原判断最低位
        while (true) {
// i>>>1<<1 == i 说明最低位是 0
            if(i >>> 1 << 1 == i){
                // 未签到，结束
// 提前结束统计
                break;
// else 分支
            }else{
                // 说明签到了
// 已签到，计数 +1
                count2++;
// if-else 结束
            }

// 无符号右移一位
            i >>>= 1;
// 写法二循环结束
        }
// 记法二结束时间
        long t4 = System.nanoTime();
// 输出写法二耗时
        System.out.println("time2 = " + (t4 - t3));
// 输出写法二统计出的连续签到天数
        System.out.println("count2 = " + count2);
// 用例方法结束
    }
// NormalTest 类结束
}