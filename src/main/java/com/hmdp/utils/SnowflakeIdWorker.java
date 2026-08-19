package com.hmdp.utils;

/**
 * 雪花算法生成器（Snowflake）
 * <p>
 * 结构：1位符号位 | 41位时间戳(相对) | 5位数据中心 | 5位机器ID | 12位序列号
 * <p>
 * 作者：小菲 (Taffy)
 */
public class SnowflakeIdWorker {

    /**
     * 起始时间戳 (2024-01-01 00:00:00)
     * 这里的值设得越晚，能撑的时间越久喵！
     */
    private static final long BEGIN_TIMESTAMP = 1704067200L;

    /**
     * 每一部分占用的位数
     */
    // 数据中心 ID 所占位数
    private static final long DATA_CENTER_BITS = 5;
    // 机器 ID 所占位数
    private static final long WORKER_BITS = 5;
    // 序列号所占位数
    private static final long SEQUENCE_BITS = 12;

    /**
     * 每一部分的最大值（掩码）
     * 计算方式：-1L << bits  取反
     */
    // 数据中心最大值：31
    private static final long MAX_DATA_CENTER_ID = ~(-1L << DATA_CENTER_BITS);
    // 机器最大值：31
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_BITS);
    // 序列号最大值：4095
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    /**
     * 每一部分向左的位移
     */
    // 机器 ID 的位移 = 12
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    // 数据中心的位移 = 12 + 5 = 17
    private static final long DATA_CENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    // 时间戳的位移 = 12 + 5 + 5 = 22
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATA_CENTER_BITS;

    /**
     * 核心参数
     */
    private final long dataCenterId;  // 数据中心 ID
    private final long workerId;      // 机器 ID
    private long sequence = 0L;       // 序列号
    private long lastTimestamp = -1L; // 上次生成 ID 的时间戳

    /**
     * 构造器：必须传入 数据中心ID 和 机器ID
     * @param dataCenterId 数据中心 ID (0~31)
     * @param workerId     机器 ID (0~31)
     */
    public SnowflakeIdWorker(long dataCenterId, long workerId) {
        if (dataCenterId > MAX_DATA_CENTER_ID || dataCenterId < 0) {
            throw new IllegalArgumentException("数据中心 ID 不能大于 " + MAX_DATA_CENTER_ID + " 或小于 0");
        }
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("机器 ID 不能大于 " + MAX_WORKER_ID + " 或小于 0");
        }
        this.dataCenterId = dataCenterId;
        this.workerId = workerId;
    }

    /**
     * 生成下一个 ID（线程安全！）
     * @return long 类型的雪花 ID
     */
    public synchronized long nextId() {
        // 1. 获取当前时间戳（秒转毫秒）
        long currentTimestamp = System.currentTimeMillis();

        // 2. 时钟回拨检查（如果当前时间小于上次生成的时间，说明服务器时间出问题了，抛异常喵！）
        if (currentTimestamp < lastTimestamp) {
            throw new RuntimeException("时钟回拨异常，拒绝生成 ID！");
        }

        // 3. 如果是同一毫秒内，序列号自增
        if (currentTimestamp == lastTimestamp) {
            // 序列号掩码运算，保证 sequence 在 0~4095 之间循环
            sequence = (sequence + 1) & SEQUENCE_MASK;
            // 4. 如果序列号满了（4096 个），说明这一毫秒用光了，就等到下一毫秒喵！
            if (sequence == 0) {
                currentTimestamp = getNextTimestamp(lastTimestamp);
            }
        } else {
            // 5. 不同毫秒，序列号重置为 0
            sequence = 0L;
        }

        // 6. 更新上一次的时间戳
        lastTimestamp = currentTimestamp;

        // 7. 拼接返回：
        // (当前时间 - 起始时间) 左移 22 位
        // | 数据中心 ID 左移 17 位
        // | 机器 ID 左移 12 位
        // | 序列号
        return ((currentTimestamp - BEGIN_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (dataCenterId << DATA_CENTER_SHIFT)
                | (workerId << WORKER_SHIFT)
                | sequence;
    }

    /**
     * 阻塞获取下一毫秒的时间戳（如果当前毫秒用光了，就要等）
     */
    private long getNextTimestamp(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    // ---------- 单元测试入口 ----------
    public static void main(String[] args) {
        // 假设我们在 数据中心 1 的 机器 1 上
        SnowflakeIdWorker idWorker = new SnowflakeIdWorker(1, 1);

        // 生成 10 个 ID 看看！
        for (int i = 0; i < 10; i++) {
            long id = idWorker.nextId();
            System.out.println("生成的雪花 ID: " + id);
        }
    }
}
