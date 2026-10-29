import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Bounded localhost-only due-message replay, shared by both application versions. */
public class BenchmarkCloseLoad {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Output path; optional burst");
        boolean burst = args.length == 2 && "burst".equals(args[1]);
        DefaultMQProducer producer = new DefaultMQProducer("benchmark-close-replay");
        producer.setNamesrvAddr("127.0.0.1:19876");
        producer.setRetryTimesWhenSendFailed(0);
        producer.start();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256));
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        long start = System.nanoTime();
        try (BufferedWriter log = Files.newBufferedWriter(Paths.get(args[0]),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            for (int i=0;i<54000;i++) {
                final int index=i;
                final long due=burst ? start : start+i*1000000000L/60;
                while (!burst && System.nanoTime()<due) Thread.sleep(1);
                // Bulk offers immediately; steady mode fails rather than silently lowering 60/s.
                while (burst && pool.getQueue().remainingCapacity()==0) Thread.sleep(1);
                futures.add(pool.submit(() -> {
                    long begin=System.nanoTime();
                    String body="{\"orderId\":"+(770000000000000000L+index)+",\"voucherId\":"+(919900+index/27000)+",\"userId\":"+(1000000+index%27000)+"}";
                    SendResult sent=producer.send(new Message("topic-order-close",body.getBytes(StandardCharsets.UTF_8)));
                    if (sent.getSendStatus()!=SendStatus.SEND_OK) throw new IllegalStateException(sent.getSendStatus().toString());
                    synchronized(log) {
                        log.write("{\"index\":"+index+",\"sent_at_ms\":"+System.currentTimeMillis()+",\"lag_ms\":"+(burst?0:(begin-due)/1000000.0)+",\"send_ms\":"+(System.nanoTime()-begin)/1000000.0+"}\n");
                        if (index%600==0) log.flush();
                    }
                    return null;
                }));
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            for (Future<?> future:futures) future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        } finally { pool.shutdownNow();producer.shutdown(); }
    }
}
