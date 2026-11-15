// 包声明：学习性验证代码
package com.hmdp;

// 通配导入并发工具包（线程池、队列、拒绝策略）
import java.util.concurrent.*;

// 学习性验证类：观察自定义线程池参数与拒绝策略的实际行为
public class C2_CustomThreadPool {
// main 方法体：建池并提交过量任务
    public static void main(String[] args) {
        // 创建线程池
// 显式 new ThreadPoolExecutor（对比 Executors 工厂，观察 7 个参数的作用）
        ExecutorService threadPool = new ThreadPoolExecutor(
// 核心线程数 10：常驻线程
                10,                              // 核心线程数
// 最大线程数 20：队列满后可扩容到 20
                20,                              // 最大线程数
// 空闲存活时间 0：非核心线程用完立即回收
                0L,                              // 空闲存活时间
// 存活时间单位：秒
                TimeUnit.SECONDS,                // 时间单位
// 任务队列容量 10：核心线程满后任务先入队
                new ArrayBlockingQueue<>(10),    // 任务队列，容量 10
// 线程工厂：默认命名 pool-N-thread-M
                Executors.defaultThreadFactory(),// 线程工厂
// 拒绝策略 AbortPolicy：队列与线程全满时抛 RejectedExecutionException
                new ThreadPoolExecutor.AbortPolicy() // 拒绝策略
// 构造结束
        );

        // 提交 100 个任务
// try-catch：捕获第 91 个任务起的拒绝异常
        try {
// 提交 100 个任务（远超 20 线程 + 10 队列的容量）
            for (int i = 1; i <= 100; i++) {
// 构造带编号的任务
                MyTask task = new MyTask(i);
// 交给线程池执行
                threadPool.execute(task);
// 提交循环结束
            }
// 捕获拒绝异常
        } catch (Exception e) {
// 打印堆栈（预期看到 RejectedExecutionException）
            e.printStackTrace();
// finally：无论成败都关闭线程池
        } finally {
// shutdown：不再接收新任务，已提交任务继续执行
            threadPool.shutdown();
// main 方法结束
        }
// C2_CustomThreadPool 类结束
    }
}

// 任务类：模拟一个耗时项目
class MyTask implements Runnable {
// 任务编号字段
    int i = 0;

// 构造器：记录任务编号
    public MyTask(int i) {
// 保存编号
        this.i = i;
// 构造器结束
    }

// 重写 Runnable
    @Override
// 任务执行体
    public void run() {
// 打印哪个线程在做第几个项目（观察线程复用与扩容）
        System.out.println(Thread.currentThread().getName() + "程序员做第" + i + "个项目");
// try-catch 包裹睡眠
        try {
// 业务逻辑：模拟耗时 3 秒（制造任务堆积）
            Thread.sleep(3000L); // 业务逻辑：模拟耗时 3 秒
// 中断异常捕获
        } catch (Exception e) {
// 打印堆栈
            e.printStackTrace();
// catch 块结束
        }
// run 方法结束
    }
// MyTask 类结束
}