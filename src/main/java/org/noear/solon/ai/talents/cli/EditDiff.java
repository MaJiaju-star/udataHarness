package org.noear.solon.ai.talents.cli;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.ChangeDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.Patch;
import com.udata.harness.common.context.ContextArtifactStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 基于本次实际写入前后内容生成可审查补丁，不向模型重复发送整个 diff。 */
final class EditDiff {
    static final int VISIBLE_PATCH_CHARS = 64000;
    final Map<String, Object> details;
    private final String fullPatch;

    private EditDiff(Map<String, Object> details, String fullPatch) {
        this.details = details;
        this.fullPatch = fullPatch;
    }

    /**
     * 创建结构化编辑结果，大补丁保留有界预览并尽力归档完整正文。
     *
     * @param path 模型请求的文件路径
     * @param before 写入前的真实内容
     * @param after 本次实际提交的内容
     * @return 待文件成功提交后归档的补丁和有界 UI 元数据
     */
    static EditDiff create(String path, String before, String after) {
        //1. 保留行终止符参与匹配，末尾换行变化也必须成为真实差异。
        List<String> oldLines = lines(before), newLines = lines(after);
        Patch<String> changes;
        boolean coarse = (long) oldLines.size() * newLines.size() > 4_000_000L;
        if (coarse) {
            int start = 0, endOld = oldLines.size(), endNew = newLines.size();
            while (start < endOld && start < endNew && oldLines.get(start).equals(newLines.get(start))) start++;
            while (endOld > start && endNew > start && oldLines.get(endOld - 1).equals(newLines.get(endNew - 1))) {
                endOld--; endNew--;
            }
            changes = new Patch<>();
            if (start != endOld || start != endNew) changes.addDelta(new ChangeDelta<>(
                    new Chunk<>(start, oldLines.subList(start, endOld)),
                    new Chunk<>(start, newLines.subList(start, endNew))));
        } else {
            changes = DiffUtils.diff(oldLines, newLines);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        List<Map<String, Object>> hunks = new ArrayList<>();
        int added = 0, deleted = 0, first = 0;
        for (AbstractDelta<String> delta : changes.getDeltas()) {
            added += delta.getTarget().size();
            deleted += delta.getSource().size();
            if (first == 0) first = delta.getTarget().getPosition() + 1;
            Map<String, Object> hunk = new LinkedHashMap<>();
            hunk.put("oldStart", delta.getSource().getPosition() + 1);
            hunk.put("oldLines", delta.getSource().size());
            hunk.put("newStart", delta.getTarget().getPosition() + 1);
            hunk.put("newLines", delta.getTarget().size());
            hunks.add(hunk);
        }
        //2. 标准 unified diff 的显示统一用 LF，无末尾换行的行显式标注。
        String safePath = path.replace('\r', '_').replace('\n', '_');
        StringBuilder patch = new StringBuilder();
        int index = 0;
        for (String line : UnifiedDiffUtils.generateUnifiedDiff("a/" + safePath, "b/" + safePath, oldLines, changes, 3)) {
            boolean body = index++ >= 2 && !line.startsWith("@@");
            if (body && (line.endsWith("\n") || line.endsWith("\r"))) {
                patch.append(line.endsWith("\r\n") ? line.substring(0, line.length() - 2)
                        : line.substring(0, line.length() - 1)).append('\n');
            } else {
                patch.append(line).append('\n');
                if (body) patch.append("\\ No newline at end of file\n");
            }
        }
        String fullPatch = patch.toString();
        boolean truncated = fullPatch.length() > VISIBLE_PATCH_CHARS;
        int end = Math.min(fullPatch.length(), VISIBLE_PATCH_CHARS);
        if (truncated) end = Math.max(0, fullPatch.lastIndexOf('\n', end - 1) + 1);
        details.put("filePath", path);
        details.put("addedLines", added);
        details.put("deletedLines", deleted);
        details.put("firstChangedLine", first);
        details.put("hunks", hunks.size() <= 200 ? hunks : new ArrayList<>(hunks.subList(0, 200)));
        details.put("hunksTruncated", hunks.size() > 200);
        details.put("coarse", coarse);
        details.put("bomPreserved", before.startsWith("\uFEFF") == after.startsWith("\uFEFF"));
        details.put("patch", fullPatch.substring(0, end));
        details.put("truncated", truncated);
        return new EditDiff(details, fullPatch);
    }

    /**
     * 仅在文件已经成功提交后归档大补丁，归档失败不把成功编辑误报为失败。
     *
     * @param store 可选归档仓储
     * @param sessionId 所属会话
     */
    void archive(ContextArtifactStore store, String sessionId) {
        if (Boolean.TRUE.equals(details.get("truncated")) && store != null && sessionId != null) {
            try {
                ContextArtifactStore.Artifact artifact = store.save(sessionId, "edit-diff:" + UUID.randomUUID(),
                        "edit", fullPatch, "edit_diff", 0);
                details.put("patchPath", "@tool-results/" + artifact.id + ".txt");
            } catch (IOException e) {
                details.put("archiveError", "完整补丁归档失败，当前仅有截断预览");
            }
        }
    }

    /** 拆分物理行并保留每行末尾换行，空文件没有行。 */
    private static List<String> lines(String text) {
        if (text.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>(Arrays.asList(text.split("(?<=\n)|(?<=\r)(?!\n)", -1)));
        if (result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
        return result;
    }
}
