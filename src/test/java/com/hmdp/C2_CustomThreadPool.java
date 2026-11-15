package com.hmdp;

import java.util.concurrent.*;

public class C2_CustomThreadPool {
    public static void main(String[] args) {
        // 创建线程池
        ExecutorService threadPool = new ThreadPoolExecutor(
                10,                              // 核心线程数
                20,                              // 最大线程数
                0L,                              // 空闲存活时间
                TimeUnit.SECONDS,                // 时间单位
                new ArrayBlockingQueue<>(10),    // 任务队列，容量 10
                Executors.defaultThreadFactory(),// 线程工厂
                new ThreadPoolExecutor.AbortPolicy() // 拒绝策略
        );

        // 提交 100 个任务
        try {
            for (int i = 1; i <= 100; i++) {
                MyTask task = new MyTask(i);
                threadPool.execute(task);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
    }
}

class MyTask implements Runnable {
    int i = 0;

    public MyTask(int i) {
        this.i = i;
    }

    @Override
    public void run() {
        System.out.println(Thread.currentThread().getName() + "程序员做第" + i + "个项目");
        try {
            Thread.sleep(3000L); // 业务逻辑：模拟耗时 3 秒
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}