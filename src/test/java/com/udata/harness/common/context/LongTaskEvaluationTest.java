package com.udata.harness.common.context;

import com.udata.harness.common.support.PersistentContextCompressionInterceptor;
import com.udata.harness.repository.ModelContextAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real file IO/compiler/acceptance, scripted model actions. Does not measure real model intelligence. */
class LongTaskEvaluationTest {
    @TempDir Path directory;
    private Path workspace;
    private boolean expectNewApi;
    private final AtomicInteger toolCalls = new AtomicInteger(), summaries = new AtomicInteger(), ids = new AtomicInteger();
    private final List<String> requests = new ArrayList<>();
    private final List<Map<String,Object>> validations = new ArrayList<>();
    private static final String KEEP = "Keep oldCalc API", ALLOW = "Allow newCalc API";
    private String userChange = "Allow API rename to newCalc and update B; keep JDK8 and rerun current-code acceptance.";

    @Test void requirementChangeAtoBtoAAndFourCompactionsPassIndependentJavaAcceptance() throws Throwable {
        seed();
        ModelContextAgentSession first = session();
        List<Supplier<AssistantMessage>> actions = new ArrayList<>();
        actions.add(action("task_state_update", map("patch",map("objective","Fix B while preserving A API", "constraints",Arrays.asList(KEEP,"Use JDK8"),
                "acceptance",Collections.singletonList("B.twice(3) == 8"),"pending",Collections.singletonList("Run current-code acceptance"),"module","A"))));
        actions.add(action("bash",map("command","test")));
        reads(actions,8);
        actions.add(action("task_state_update",map("patch",map("module","B"))));
        actions.add(action("context_checkpoint",map("reason","A explored; switch to B")));
        actions.add(action("read",map("file_path","B.java")));
        actions.add(action("fixture_edit",map("file_path","B.java","content",b("oldCalc"))));
        actions.add(action("bash",map("command","test")));
        actions.add(() -> call("task_state_update", map("patch",map("completed",Collections.singletonList("B repaired with old API"),
                "evidence",Collections.singletonList(testArtifact(first)),"pending",Collections.singletonList("Review later requirement changes")))));
        logs(actions,8);
        actions.add(action("context_checkpoint",map("reason","B repaired and tested")));
        actions.add(() -> ChatMessage.ofAssistant("First phase finished"));
        run(first,actions,"Fix B while keeping oldCalc API and JDK8.");
        assertTrue((Boolean) validations.get(1).get("passed"));
        String oldPassingHash=(String)validations.get(1).get("codeHash");

        ModelContextAgentSession resumed=session();
        expectNewApi=true;
        List<Supplier<AssistantMessage>> continuation=new ArrayList<>();
        continuation.add(action("task_state_get",Collections.emptyMap()));
        continuation.add(() -> call("task_state_revise",map("expectedRevision",resumed.taskState().get().get("revision"),
                "source_quote","Allow API rename to newCalc and update B","reason","User replaced the API constraint",
                "patch",map("objective","Rename A API and update B","constraints",Arrays.asList(ALLOW,"Use JDK8"),
                        "acceptance",Arrays.asList("A.newCalc(3) == 4","B.twice(3) == 8")))));
        // Deliberately simulate old-summary contamination; regular update must reject it.
        continuation.add(action("task_state_update",map("patch",map("constraints",Collections.singletonList(KEEP)))));
        continuation.add(action("task_state_update",map("patch",map("module","A"))));
        reads(continuation,6);
        continuation.add(action("fixture_edit",map("file_path","A.java","content",a("newCalc"))));
        continuation.add(action("bash",map("command","test"))); // B still references the old API: old passing result is stale.
        continuation.add(action("task_state_update",map("patch",map("pending",Collections.singletonList("B references oldCalc; rerun tests after updating B")))));
        continuation.add(action("context_checkpoint",map("reason","A renamed; B repair remains pending")));
        continuation.add(action("read",map("file_path","B.java")));
        continuation.add(action("fixture_edit",map("file_path","B.java","content",b("newCalc"))));
        continuation.add(action("bash",map("command","test")));
        continuation.add(() -> call("task_state_update",map("patch",map("completed",Collections.singletonList("API rename and B verified"),
                "pending",Collections.emptyList(),"evidence",Collections.singletonList(testArtifact(resumed))))));
        logs(continuation,8);
        continuation.add(action("context_checkpoint",map("reason","New API and B passed current-code acceptance")));
        continuation.add(() -> call("context_search",map("query",codeHash(),"tool","bash")));
        continuation.add(() -> call("context_restore",map("id",testArtifact(resumed),"offset",0,"maxChars",1200)));
        continuation.add(() -> ChatMessage.ofAssistant("Delivery ready"));
        run(resumed,continuation,userChange);

        assertFalse((Boolean)validations.get(0).get("passed"));
        assertFalse((Boolean)validations.get(2).get("passed"));
        assertTrue((Boolean)validations.get(3).get("passed"));
        assertNotEquals(oldPassingHash,codeHash());
        assertTrue(validate()); // Independent acceptance, outside the Agent's tool and final-answer claims.
        ModelContextAgentSession reopened=session();
        assertEquals(Arrays.asList(ALLOW,"Use JDK8"),reopened.taskState().get().get("constraints"));
        assertEquals(Collections.emptyList(),reopened.taskState().get().get("pending"));
        assertEquals("Rename A API and update B",reopened.taskState().get().get("objective"));
        assertTrue(((Number)reopened.contextReport().get("generation")).longValue()>=4);
        assertEquals(4,summaries.get());
        assertTrue(requests.stream().anyMatch(r->r.contains("Superseded requirement cannot be reintroduced")));
        String snapshot=reopened.getLatestMessages(200).get(0).getContent();
        assertTrue(snapshot.contains(ALLOW)); assertFalse(snapshot.contains(KEEP));
        assertTrue(reopened.contextArtifacts().restore(testArtifact(reopened),0,1200).get("content").toString().contains(codeHash()));
        Map<String,Object> report=map("mode","scripted_replay_with_real_java_acceptance","providerInvoked",false,
                "independentAcceptancePassed",true,"validations",validations,"contextGenerations",reopened.contextReport().get("generation"),
                "summaries",summaries.get(),"toolCalls",toolCalls.get(),"modelRequests",requests.size(),
                "requestCharacters",requests.stream().mapToLong(String::length).sum(),"requirementRevisionSurvivedRestart",true,
                "supersededConstraintRejected",true,"oldPassingEvidenceWasStale",true,"metrics",reopened.contextMetrics().report(),
                "limitation","Scripted model actions do not establish real-model quality, true token savings, cache benefit or superiority over Claude Code.");
        Files.write(Paths.get("target/context-long-task-evaluation.json"),ONode.serialize(report).getBytes(StandardCharsets.UTF_8));
    }

    @Test void independentAcceptanceRejectsFalseCompletionAndOldApiAfterRequirementChange() throws Throwable {
        seed(); ModelContextAgentSession session=session();
        run(session,Arrays.asList(action("task_state_update",map("patch",map("objective","Fix B","completed",Collections.singletonList("Everything passed")))),
                () -> ChatMessage.ofAssistant("All tests passed")),"Fix B and verify current code");
        assertFalse(validate());
        Files.write(workspace.resolve("B.java"),b("oldCalc").getBytes(StandardCharsets.UTF_8));
        assertTrue(validate()); expectNewApi=true; assertFalse(validate());
    }

    private void seed() throws Exception {
        workspace=directory.resolve("workspace");Files.createDirectories(workspace);
        Files.write(workspace.resolve("A.java"),a("oldCalc").getBytes(StandardCharsets.UTF_8));
        Files.write(workspace.resolve("B.java"),"public class B { public static int twice(int x) { return x * 2; } }".getBytes(StandardCharsets.UTF_8));
    }
    private String a(String method) { return "public class A {\n"+String.join("",Collections.nCopies(100,"// source padding for long-context fixture abcdefghijklmnopqrstuvwxyz\n"))
            +"public static int "+method+"(int x) { return x + 1; }\n}\n"; }
    private String b(String method) { return "public class B { public static int twice(int x) { return A."+method+"(x) * 2; } }\n"; }
    private String codeHash() { try {return ContextArtifactStore.digest(new String(Files.readAllBytes(workspace.resolve("A.java")),StandardCharsets.UTF_8)+"\n"+new String(Files.readAllBytes(workspace.resolve("B.java")),StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);} }
    private boolean validate() throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler,"Evaluation requires the JDK compiler");
        Path classes=Files.createTempDirectory(directory,"compiled-");ByteArrayOutputStream errors=new ByteArrayOutputStream();
        int exit=compiler.run(null,errors,errors,"-d",classes.toString(),workspace.resolve("A.java").toString(),workspace.resolve("B.java").toString());
        boolean passed=false;
        if(exit==0)try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},null)) {
            passed=Objects.equals(4,loader.loadClass("A").getMethod(expectNewApi?"newCalc":"oldCalc",int.class).invoke(null,3))
                    && Objects.equals(8,loader.loadClass("B").getMethod("twice",int.class).invoke(null,3));
        }catch(ReflectiveOperationException ignored){}
        validations.add(map("passed",passed,"codeHash",codeHash(),"expectedApi",expectNewApi?"newCalc":"oldCalc","compilerExit",exit));return passed;
    }
    private String testArtifact(ModelContextAgentSession session) { return (String)session.contextArtifacts().search("codeHash="+codeHash(),"bash",1).get(0).get("id"); }
    private ModelContextAgentSession session() { return new ModelContextAgentSession("long-task",directory.resolve("sessions").toString()); }
    private void reads(List<Supplier<AssistantMessage>> actions,int count) { for(int i=0;i<count;i++)actions.add(action("read",map("file_path","A.java","offset",1,"limit",90))); }
    private void logs(List<Supplier<AssistantMessage>> actions,int count) { for(int i=0;i<count;i++)actions.add(action("bash",map("command","diagnostics"))); }
    private Supplier<AssistantMessage> action(String name,Map<String,Object> args) { return ()->call(name,args); }
    private AssistantMessage call(String name,Map<String,Object> args) {
        String id="eval-"+ids.incrementAndGet();return new AssistantMessage("",null,false,null,null,
                Collections.singletonList(new ToolCall(id,id,name,ONode.serialize(args),args)),null);
    }
    private FunctionTool function(String name,Function<Map<String,Object>,String> handler) {
        return new FunctionTool(){public String name(){return name;}public String description(){return "Local evaluation fixture";}public String title(){return name;}
            public boolean returnDirect(){return false;}public Type returnType(){return String.class;}public String inputSchema(){return "{\"type\":\"object\"}";}
            public Object handle(Map<String,Object> args){toolCalls.incrementAndGet();return handler.apply(args);}};
    }
    private void run(ModelContextAgentSession session,List<Supplier<AssistantMessage>> actions,String prompt) throws Throwable {
        AtomicInteger step=new AtomicInteger();
        ChatModel model=ChatModel.of("http://127.0.0.1:1").model("evaluation").provider("openai").contextLength(1000000)
                .defaultInterceptorAdd(new ChatInterceptor(){public ChatResponse interceptCall(ChatRequest req,CallChain chain){
                    requests.add(req.toRequestData());AssistantMessage message=actions.get(step.getAndIncrement()).get();
                    return (ChatResponse)Proxy.newProxyInstance(ChatResponse.class.getClassLoader(),new Class<?>[]{ChatResponse.class},(p,m,a)->{
                        switch(m.getName()){case "getMessage":return message;case "getContent":case "getText":return message.getContent();case "getToolCalls":return message.getToolCalls();
                            case "getFinishReason":return message.isToolCalls()?"tool_calls":"stop";case "isTerminal":case "hasContent":return true;case "isEmpty":return false;default:return null;}
                    });}}).build();
        PersistentContextCompressionInterceptor compression=new PersistentContextCompressionInterceptor(1000,0.75,1,2,(m,r,t,old)->{
            summaries.incrementAndGet();return ChatMessage.ofUser("Historical phase explored A and repaired B. Requirements may be superseded; consult active task state.");}).contextManagement(2000,4);
        ReActAgent.Builder builder=ReActAgent.of(model).name(AgentDefinition.AGENT_MAIN).sessionWindowSize(200).maxTurns(60)
                .defaultInterceptorAdd(compression).defaultInterceptorAdd(Integer.MIN_VALUE,new ModelCallObserver());
        builder.defaultToolAdd(function("read",args->{try{
            String file=(String)args.get("file_path");if(!Arrays.asList("A.java","B.java").contains(file))throw new IllegalArgumentException("Unknown fixture file");
            List<String> lines=Files.readAllLines(workspace.resolve(file),StandardCharsets.UTF_8);int start=((Number)args.getOrDefault("offset",1)).intValue(),end=Math.min(lines.size(),start+((Number)args.getOrDefault("limit",lines.size())).intValue()-1);
            StringBuilder out=new StringBuilder("--- File: "+file+" (Lines: "+start+" - "+end+", Size: 10.00 KB) ---\n--------------------------------------------------\n");
            for(int i=start;i<=end;i++)out.append(String.format("%6d | %s\n",i,lines.get(i-1)));return out.append("\n--- [文件读取完毕] ---").toString();
        }catch(Exception e){throw new RuntimeException(e);}}));
        builder.defaultToolAdd(function("fixture_edit",args->{try{String file=(String)args.get("file_path");if(!Arrays.asList("A.java","B.java").contains(file))throw new IllegalArgumentException("Unknown fixture file");
            Files.write(workspace.resolve(file),((String)args.get("content")).getBytes(StandardCharsets.UTF_8));return "File updated; previous test results may be stale";}catch(Exception e){throw new RuntimeException(e);}}));
        builder.defaultToolAdd(function("bash",args->{try{
            if("diagnostics".equals(args.get("command")))return String.join("",Collections.nCopies(300,"diagnostic progress line\n"));
            if(!"test".equals(args.get("command")))throw new IllegalArgumentException("Unknown fixture command");
            boolean passed=validate();return "Tests run: 2, Failures: "+(passed?0:1)+"\ncodeHash="+codeHash()+"\n"+(passed?"BUILD SUCCESS":"BUILD FAILURE")+"\n";
        }catch(Exception e){throw new RuntimeException(e);}}));
        for(FunctionTool tool:ContextTools.definitions())builder.defaultToolAdd(tool);
        builder.build().prompt(prompt).session(session).call();assertEquals(actions.size(),step.get());
    }
    private static Map<String,Object> map(Object...pairs){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;}
}
