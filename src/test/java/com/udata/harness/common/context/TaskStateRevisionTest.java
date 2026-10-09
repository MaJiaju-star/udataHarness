package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TaskStateRevisionTest {
    @TempDir Path directory;
    private TaskStateStore store() { return new TaskStateStore(directory,new ContextArtifactStore(directory)); }
    private Map<String,Object> initial() {
        Map<String,Object> patch=new LinkedHashMap<>(); patch.put("objective","Fix A and B");
        patch.put("constraints",Arrays.asList("Keep old API","Use JDK8")); patch.put("pending",Collections.singletonList("Rerun acceptance")); return patch;
    }
    private Map<String,Object> changed() {
        Map<String,Object> patch=new LinkedHashMap<>(); patch.put("objective","Rename API and fix B"); patch.put("constraints",Arrays.asList("Allow API rename","Use JDK8")); return patch;
    }
    private final String request="Change requirements: allow API rename and update module B";
    @Test void amendmentsRetireRequirementsKeepPendingAndSurviveRestart() {
        TaskStateStore state=store(); state.update(initial());
        state.revise(1,changed(),"allow API rename","User changed API requirement",request);
        Map<String,Object> current=store().get();
        assertEquals("Rename API and fix B",current.get("objective"));
        assertEquals(Arrays.asList("Allow API rename","Use JDK8"),current.get("constraints"));
        assertEquals(Collections.singletonList("Rerun acceptance"),current.get("pending"));
        assertEquals(1,((Number)store().historyPreview(5).get("totalRevisions")).intValue());
        assertTrue(ONode.serialize(store().historyPreview(5)).contains("Keep old API"));
        assertFalse(ONode.serialize(current.get("constraints")).contains("Keep old API"));
        assertThrows(IllegalArgumentException.class,()->state.update(Collections.singletonMap("constraints",Collections.singletonList("Keep old API"))));
        assertThrows(IllegalArgumentException.class,()->state.update(Collections.singletonMap("objective","Undo rename")));
    }
    @Test void staleRevisionInventedSourceAndInvalidPatchDoNotModifyDisk() {
        TaskStateStore state=store(); state.update(initial()); String before=ONode.serialize(state.get());
        assertThrows(IllegalArgumentException.class,()->state.revise(0,changed(),"allow API rename","reason",request));
        assertThrows(IllegalArgumentException.class,()->state.revise(1,changed(),"invented user instruction","reason",request));
        assertThrows(IllegalArgumentException.class,()->state.revise(1,Collections.singletonMap("pending",Collections.emptyList()),"allow API rename","reason",request));
        assertEquals(before,ONode.serialize(store().get())); assertEquals(0,((Number)store().historyPreview(5).get("totalRevisions")).intValue());
    }
    @Test void legacyStateMigratesAndStateAndAuditFailureIsAtomic() throws Exception {
        Files.write(directory.resolve("task-state.json"),ONode.serialize(initial()).getBytes(StandardCharsets.UTF_8));
        TaskStateStore state=store(); assertEquals("Fix A and B",state.get().get("objective"));
        state.update(Collections.singletonMap("phase","development"));
        String before=ONode.serialize(state.get());
        Map<String,Object> tooLarge=new LinkedHashMap<>(changed()); tooLarge.put("acceptance",Collections.nCopies(100,String.join("",Collections.nCopies(1900,"x"))));
        // Distinct items are needed to exceed the active-state cap after de-duplication.
        List<String> items=new ArrayList<>(); for(int i=0;i<100;i++)items.add(i+String.join("",Collections.nCopies(1900,"x"))); tooLarge.put("acceptance",items);
        assertThrows(IllegalArgumentException.class,()->state.revise(1,tooLarge,"allow API rename","reason",request));
        assertEquals(before,ONode.serialize(store().get())); assertEquals(0,((Number)state.historyPreview(20).get("totalRevisions")).intValue());
    }
    @Test void concurrentStoresCannotBothCommitTheSameRevision() throws Exception {
        store().update(initial()); CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1); AtomicInteger committed=new AtomicInteger();
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(executor.submit(()->{ ready.countDown(); try{start.await();store().revise(1,changed(),"allow API rename","reason",request);committed.incrementAndGet();}catch(IllegalArgumentException expected){}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);} }));
            assertTrue(ready.await(2,TimeUnit.SECONDS));start.countDown();for(Future<?> future:futures)future.get(3,TimeUnit.SECONDS);
            assertEquals(1,committed.get());assertEquals(1,((Number)store().historyPreview(5).get("totalRevisions")).intValue());
        } finally { start.countDown();executor.shutdownNow(); }
    }
}
