package com.gjf.village.common.util;

import com.gjf.village.entity.SysSensitiveWord;
import com.gjf.village.mapper.SysSensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 敏感词过滤器(DFA 确定有限自动机 / Trie 树)。
 *
 * <p><b>为什么不能写成循环 contains:</b>
 * <pre>
 *   for (String word : allWords) {
 *       if (content.contains(word)) { ... }
 *   }
 * </pre>
 * 这种写法是 O(词数 × 文本长度)。1000 个敏感词、1000 字的留言,
 * 就是 100 万次字符串匹配 —— 每条留言都要跑一遍,接口会明显变慢。
 *
 * <p><b>DFA 的做法:</b>把所有词预先建成一棵树,扫描文本时字符沿着树走,
 * 走不动就回退。复杂度是 O(文本长度 × 匹配深度),与词库大小无关。
 * 1000 个词和 10 个词,扫描同样一段文本的耗时基本一样。
 *
 * <p>举个例子,词库是 {"赌博", "赌场"} 时建出的树:
 * <pre>
 *   root ── 赌 ── 博(词尾)
 *            └── 场(词尾)
 * </pre>
 * 扫到"赌"字就进入这个节点,再看下一个字决定往哪走。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordUtil {

    private final SysSensitiveWordMapper sensitiveWordMapper;

    /**
     * Trie 节点。
     *
     * <p>用一个小类而不是「Map 里塞一个哨兵字符表示词尾」那种写法,原因有二:
     * <ol>
     *   <li><b>不会踩编码的坑</b> —— 哨兵写法要么用空格(会和含空格的敏感词冲突),
     *       要么用 null 字符(源码里嵌裸的 0x00 会让文件被当成二进制,
     *       grep/diff/Git 都可能处理异常,编码转换还可能把它弄丢)</li>
     *   <li><b>类型安全</b> —— 子节点是 {@code Map<Character, Node>},
     *       取值不需要 {@code (Map<Character, Object>)} 这种强转,
     *       编译期就能保证结构正确</li>
     * </ol>
     */
    private static class Node {
        /** 子节点:字符 → 下一个节点 */
        final Map<Character, Node> children = new HashMap<>();
        /** 从根走到本节点是否构成一个完整的敏感词 */
        boolean isEnd;
    }

    /** Trie 的根节点 */
    private volatile Node root = new Node();

    /** 命中后的处理方式:词 → 动作(1替换 2拦截) */
    private volatile Map<String, Integer> actionMap = new HashMap<>();

    /** 替换字符 */
    private static final char MASK_CHAR = '*';

    /** 处理动作:替换为星号 */
    private static final int ACTION_REPLACE = 1;

    /** 处理动作:直接拦截,整条内容不予发布 */
    private static final int ACTION_BLOCK = 2;

    /**
     * 启动时加载词库。
     *
     * <p>全量加载进内存而不是每次过滤都查数据库 ——
     * 过滤是高频操作(每条留言、每条消息、每条评价都要跑),
     * 每次都查库会把数据库打满。
     *
     * <p>代价是词库更新后需要调用 {@link #reload()} 刷新,
     * 后台的敏感词管理接口会在增删改后自动触发。
     */
    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            // 词库加载失败不能让整个应用起不来。
            //
            // 极端情况(比如数据库还没就绪、敏感词表被误删)下,
            // 如果这里直接抛出去,整个服务会启动失败 —— 而敏感词过滤
            // 只是一个辅助功能,不该有这么大的杀伤力。
            // 此时词库为空,过滤器退化为"不过滤",服务照常提供其他功能,
            // 管理员修复数据后调一次 /api/system/sensitive/list 触发重载即可。
            log.error("敏感词库加载失败,过滤器将暂时失效。修复数据后重新调用一次敏感词接口即可恢复", e);
        }
    }

    /**
     * 重新加载词库。
     *
     * <p>用 volatile + 整体替换而不是原地修改:过滤是多线程并发调用的,
     * 原地修改会让某个线程读到"树建了一半"的中间状态。
     * 整体替换能保证任何时刻读到的都是一棵完整的树。
     */
    public synchronized void reload() {
        Node newRoot = new Node();
        Map<String, Integer> newActions = new HashMap<>();

        List<SysSensitiveWord> words = sensitiveWordMapper.selectList(null);
        int loaded = 0;
        for (SysSensitiveWord word : words) {
            if (!Integer.valueOf(1).equals(word.getStatus())) {
                continue;   // 停用的词不加载
            }
            String text = word.getWord();
            if (text == null || text.isBlank()) {
                continue;
            }
            addWord(newRoot, text);
            newActions.put(text, word.getAction() == null ? ACTION_REPLACE : word.getAction());
            loaded++;
        }

        this.root = newRoot;
        this.actionMap = newActions;
        log.info("敏感词库加载完成,共 {} 个词", loaded);
    }

    /** 把词逐个字符挂到树上 */
    private void addWord(Node tree, String word) {
        Node node = tree;
        for (char c : word.toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new Node());
        }
        node.isEnd = true;
    }

    /**
     * 找出文本里命中的所有敏感词。
     *
     * <p>算法:从每个位置出发往树里走,能走多远走多远;
     * 途中每遇到一个词尾节点就记录一次命中。
     */
    public Set<String> findAll(String text) {
        Set<String> hits = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }

        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            Node node = root;
            StringBuilder matched = new StringBuilder();

            for (int j = i; j < chars.length; j++) {
                Node next = node.children.get(chars[j]);
                if (next == null) {
                    break;
                }
                matched.append(chars[j]);
                node = next;

                if (node.isEnd) {
                    hits.add(matched.toString());
                }
            }
        }
        return hits;
    }

    /**
     * 是否包含需要「直接拦截」的敏感词。
     *
     * <p>不是所有敏感词都要拦 —— 广告类通常只是替换成星号(内容仍可发),
     * 辱骂和违法类才直接拒绝。两种处理方式由 {@code sys_sensitive_word.action} 决定。
     */
    public boolean shouldBlock(String text) {
        return findAll(text).stream()
                .anyMatch(w -> Objects.equals(actionMap.get(w), ACTION_BLOCK));
    }

    /**
     * 过滤文本,把命中且动作为「替换」的敏感词打码。
     *
     * <p>调用方应该先调 {@link #shouldBlock} —— 命中拦截类词时整个请求就该被拒,
     * 根本不会走到这里。这个方法只处理"打码后内容仍可发布"的那部分词。
     */
    public String filter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        char[] chars = text.toCharArray();
        boolean[] masked = new boolean[chars.length];

        for (int i = 0; i < chars.length; i++) {
            Node node = root;
            int longestEnd = -1;

            for (int j = i; j < chars.length; j++) {
                Node next = node.children.get(chars[j]);
                if (next == null) {
                    break;
                }
                node = next;
                if (node.isEnd) {
                    // 记录本次能匹配到的最远位置。
                    // 取最远而不是最近是因为:词库同时有"赌博"和"赌博机"时,
                    // 应该整个"赌博机"都打码,而不是只打"赌博"两个字。
                    longestEnd = j;
                }
            }

            if (longestEnd >= 0) {
                String matched = new String(chars, i, longestEnd - i + 1);
                // 拦截类不在这里打码 —— 那种情况调用方应该已经拒绝整个请求了
                if (!Objects.equals(actionMap.get(matched), ACTION_BLOCK)) {
                    for (int k = i; k <= longestEnd; k++) {
                        masked[k] = true;
                    }
                }
                // 跳过已处理的片段,避免重叠命中被重复计数
                i = longestEnd;
            }
        }

        StringBuilder sb = new StringBuilder(chars.length);
        for (int i = 0; i < chars.length; i++) {
            sb.append(masked[i] ? MASK_CHAR : chars[i]);
        }
        return sb.toString();
    }

    /** 词库里的词数,供后台展示 */
    public int size() {
        return actionMap.size();
    }
}
