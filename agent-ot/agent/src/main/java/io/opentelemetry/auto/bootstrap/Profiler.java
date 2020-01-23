package io.opentelemetry.auto.bootstrap;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.Thread.State;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Profiler {

    public static void main(String[] args) throws InterruptedException {
        start(new PrintWriter(System.out));
        Thread.sleep(2000);
        System.exit(0);
    }

    public static void start() {
        File dir = new File("/home");
        if (!dir.exists() || !dir.isDirectory()) {
            dir = new File(".");
        }
        File file = new File(dir, "stacktraces.txt");
        try {
            start(new PrintWriter(new FileWriter(file)));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void start(PrintWriter out) {
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(new ThreadDump(out), 50, 50, TimeUnit.MILLISECONDS);
    }


    private static class ThreadDump implements Runnable {

        private final PrintWriter out;

        private ThreadDump(PrintWriter out) {
            this.out = out;
        }

        @Override
        public void run() {
            out.println("========================================");
            RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
            out.println(runtimeBean.getUptime());
            out.println();
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadBean.getAllThreadIds(),
                    threadBean.isObjectMonitorUsageSupported(), false);
            long currentThreadId = Thread.currentThread().getId();
            for (ThreadInfo threadInfo : threadInfos) {
                if (threadInfo.getThreadId() != currentThreadId) {
                    write(threadInfo);
                }
            }
            out.flush();
        }

        private void write(ThreadInfo threadInfo) {
            if (capture(threadInfo)) {

            }
            out.println(threadInfo.getThreadName() + " #" + threadInfo.getThreadId());
            out.println("   java.lang.Thread.State: " + threadInfo.getThreadState());
            for (StackTraceElement ste : threadInfo.getStackTrace()) {
                out.println("        " + ste);
            }
            out.println();
        }

        private boolean capture(ThreadInfo threadInfo) {
            if (threadInfo.getThreadName().equals("main")) {
                return true;
            }
            // check stack trace length helps to skip "Signal Dispatcher" thread
            return threadInfo.getThreadState() == State.RUNNABLE
                    && threadInfo.getStackTrace().length > 0;
        }
    }

    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
}
