package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestPrefixSnapshotTest {
    private Map<String, Object> snapshot(String body) { return RequestPrefixSnapshot.capture(body, "openai", "https://example.test/v1?api-key=ENDPOINT_SECRET"); }
    private String request(String system, String tools, String history) {
        return "{\"model\":\"test\",\"messages\":[{\"role\":\"system\",\"content\":" + ONode.serialize(system) + "}," + history + "],\"tools\":" + tools + "}";
    }
    private static final String USER = "{\"role\":\"user\",\"content\":\"PROMPT_SECRET\"}";
    private static final String A = "{\"type\":\"function\",\"function\":{\"name\":\"alpha\",\"description\":\"DESCRIPTION_SECRET\",\"parameters\":{\"type\":\"object\"}}}";
    private static final String B = "{\"type\":\"function\",\"function\":{\"name\":\"beta\"}}";

    @Test void fingerprintsNeverContainPayloadsOrEndpointCredentialsAndDetectNamedSystemSections() {
        Map<String, Object> before = snapshot(request("BASE_SECRET\n# [Talent: TerminalTalent]\nPID_SECRET", "[" + A + "]", USER));
        Map<String, Object> after = snapshot(request("BASE_SECRET\n# [Talent: TerminalTalent]\nDIFFERENT_SECRET", "[" + A + "]", USER));
        String serialized = ONode.serialize(before) + ONode.serialize(RequestPrefixSnapshot.compare(before, after));
        for (String secret : Arrays.asList("PROMPT_SECRET", "DESCRIPTION_SECRET", "ENDPOINT_SECRET", "BASE_SECRET", "PID_SECRET", "DIFFERENT_SECRET")) assertFalse(serialized.contains(secret));
        Map<?, ?> change = (Map<?, ?>) RequestPrefixSnapshot.compare(before, after).get("firstSystemDifference");
        assertEquals("talent:TerminalTalent", change.get("section"));
    }

    @Test void detectsAppendOnlyAndExactRetryWithoutChangingInput() {
        String original = request("system", "[" + A + "]", USER);
        Map<String, Object> before = snapshot(original);
        assertEquals("identical", RequestPrefixSnapshot.compare(before, snapshot(original)).get("change"));
        Map<String, Object> diff = RequestPrefixSnapshot.compare(before, snapshot(request("system", "[" + A + "]", USER + "," + USER)));
        assertEquals("append_only", diff.get("change")); assertEquals(true, diff.get("prefixPreserved")); assertEquals(2, diff.get("messagePrefixCount"));
    }

    @Test void detectsToolOrderAndChangedSchemasSeparately() {
        Map<String, Object> before = snapshot(request("system", "[" + A + "," + B + "]", USER));
        Map<String, Object> order = RequestPrefixSnapshot.compare(before, snapshot(request("system", "[" + B + "," + A + "]", USER)));
        assertEquals(true, order.get("toolOrderChanged")); assertEquals(Collections.emptyList(), order.get("changedTools"));
        String changed = A.replace("\"object\"", "\"string\"");
        Map<String, Object> diff = RequestPrefixSnapshot.compare(before, snapshot(request("system", "[" + changed + "," + B + "]", USER)));
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) diff.get("changedTools")).get(0);
        assertEquals("alpha", tool.get("name")); assertEquals(Collections.singletonList("parameters"), tool.get("fields"));
        assertEquals(false, diff.get("prefixPreserved"));
    }

    @Test void detectsDroppedToolHistoryAcrossUserTurns() {
        String assistant = "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call\"}]}";
        Map<String, Object> before = snapshot(request("system", "[]", USER + "," + assistant + ",{\"role\":\"tool\",\"content\":\"TOOL_SECRET\"}"));
        Map<String, Object> after = snapshot(request("system", "[]", USER + ",{\"role\":\"assistant\",\"content\":\"done\"}," + USER));
        Map<String, Object> diff = RequestPrefixSnapshot.compare(before, after);
        assertEquals("history_changed", diff.get("change")); assertEquals(2, ((Map<?, ?>) diff.get("firstMessageDifference")).get("index"));
    }

    @Test void understandsResponsesAndAnthropicMergedContentBlocks() {
        Map<String, Object> responses = snapshot("{\"model\":\"test\",\"instructions\":\"INSTRUCTION_SECRET\",\"input\":[{\"role\":\"user\",\"content\":\"INPUT_SECRET\"}]}");
        assertEquals("responses", responses.get("format")); assertEquals(1, responses.get("messageCount"));
        Map<String, Object> a = snapshot("{\"model\":\"test\",\"system\":[{\"type\":\"text\",\"text\":\"SYSTEM_SECRET\"}],\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"ONE_SECRET\"}]}]}");
        Map<String, Object> b = snapshot("{\"model\":\"test\",\"system\":[{\"type\":\"text\",\"text\":\"SYSTEM_SECRET\"}],\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"ONE_SECRET\"},{\"type\":\"text\",\"text\":\"TWO_SECRET\"}]}]}");
        Map<?, ?> first = (Map<?, ?>) RequestPrefixSnapshot.compare(a, b).get("firstMessageDifference");
        assertEquals(1, first.get("sharedContentBlocks")); assertEquals("anthropic", a.get("format"));
        assertFalse(ONode.serialize(a).contains("SYSTEM_SECRET"));
    }

    @Test void parameterAndRouteChangesAreNotReportedAsUnchangedPrefix() {
        String body = request("system", "[]", USER);
        Map<String, Object> before = snapshot(body);
        Map<String, Object> after = snapshot(body.substring(0, body.length() - 1) + ",\"tool_choice\":\"none\"}");
        Map<String, Object> diff = RequestPrefixSnapshot.compare(before, after);
        assertEquals("parameters_changed", diff.get("change")); assertEquals(false, diff.get("prefixPreserved"));
        assertEquals(Collections.singletonList("tool_choice"), diff.get("changedParameters"));
        Map<String, Object> route = RequestPrefixSnapshot.capture(body, "openai", "https://other.test");
        assertTrue(((List<?>) RequestPrefixSnapshot.compare(before, route).get("changedSections")).contains("endpoint"));
    }

    @Test void reportsCacheMarkerChangesInsideMergedBlocksAndUnknownFormatsConservatively() {
        String a = "{\"model\":\"test\",\"system\":\"fixed\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"SECRET\",\"cache_control\":{\"type\":\"ephemeral\"}}]}]}";
        String b = a.replace(",\"cache_control\":{\"type\":\"ephemeral\"}", "");
        Map<?, ?> first = (Map<?, ?>) RequestPrefixSnapshot.compare(snapshot(a), snapshot(b)).get("firstMessageDifference");
        assertEquals(Collections.singletonList("cache_control"), first.get("changedContentBlockFields"));
        Map<String, Object> unknown = snapshot("{\"contents\":[\"value\"]}");
        assertEquals("comparison_incomplete", RequestPrefixSnapshot.compare(unknown, unknown).get("change"));
        assertEquals(false, RequestPrefixSnapshot.compare(unknown, unknown).get("prefixPreserved"));
    }
}
