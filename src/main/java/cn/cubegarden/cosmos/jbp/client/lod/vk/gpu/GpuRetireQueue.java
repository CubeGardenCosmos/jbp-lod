package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Render-thread-polled, nonblocking destruction queue for resources held by submitted work. */
public final class GpuRetireQueue {
    private static final List<Retired> RETIRED = new ArrayList<>();

    private GpuRetireQueue() {
    }

    /** Transfers ownership of the completion token and resource to this queue. */
    public static synchronized void retire(GpuCompletion completion, AutoCloseable resource) {
        if (completion == null || resource == null) {
            throw new NullPointerException("completion and resource are required");
        }
        RETIRED.add(new Retired(completion, resource));
    }

    /** Polls and destroys only resources whose graphics-queue fence has signaled. */
    public static synchronized void poll(GameVulkan.Snapshot game) {
        Iterator<Retired> iterator = RETIRED.iterator();
        while (iterator.hasNext()) {
            Retired retired = iterator.next();
            if (retired.completion.gameDeviceAddress() != game.device().address()) {
                continue;
            }
            if (!retired.completion.isComplete()) {
                continue;
            }
            iterator.remove();
            retired.completion.close();
            try {
                retired.resource.close();
            } catch (Exception failure) {
                throw new IllegalStateException("failed closing retired GPU resource", failure);
            }
        }
    }

    private record Retired(GpuCompletion completion, AutoCloseable resource) {
    }
}
